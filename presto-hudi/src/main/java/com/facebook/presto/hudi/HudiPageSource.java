/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.presto.hudi;

import com.facebook.presto.common.Page;
import com.facebook.presto.common.Utils;
import com.facebook.presto.common.block.Block;
import com.facebook.presto.common.block.LazyBlock;
import com.facebook.presto.common.block.LazyBlockLoader;
import com.facebook.presto.common.block.RunLengthEncodedBlock;
import com.facebook.presto.common.type.DecimalType;
import com.facebook.presto.common.type.Decimals;
import com.facebook.presto.common.type.TimeZoneKey;
import com.facebook.presto.common.type.Type;
import com.facebook.presto.common.type.TypeManager;
import com.facebook.presto.common.type.VarbinaryType;
import com.facebook.presto.common.type.VarcharType;
import com.facebook.presto.hive.HiveCoercer;
import com.facebook.presto.hive.HiveType;
import com.facebook.presto.spi.ConnectorPageSource;
import com.facebook.presto.spi.PrestoException;
import com.google.common.collect.ImmutableList;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static com.facebook.presto.common.type.BigintType.BIGINT;
import static com.facebook.presto.common.type.BooleanType.BOOLEAN;
import static com.facebook.presto.common.type.DateType.DATE;
import static com.facebook.presto.common.type.Decimals.isLongDecimal;
import static com.facebook.presto.common.type.Decimals.isShortDecimal;
import static com.facebook.presto.common.type.DoubleType.DOUBLE;
import static com.facebook.presto.common.type.IntegerType.INTEGER;
import static com.facebook.presto.common.type.RealType.REAL;
import static com.facebook.presto.hive.metastore.MetastoreUtil.HIVE_DEFAULT_DYNAMIC_PARTITION;
import static com.facebook.presto.hudi.HudiErrorCode.HUDI_CURSOR_ERROR;
import static com.facebook.presto.hudi.HudiErrorCode.HUDI_INVALID_PARTITION_VALUE;
import static com.facebook.presto.spi.StandardErrorCode.NOT_SUPPORTED;
import static com.google.common.base.Throwables.throwIfInstanceOf;
import static io.airlift.slice.Slices.utf8Slice;
import static java.lang.Double.parseDouble;
import static java.lang.Float.floatToRawIntBits;
import static java.lang.Float.parseFloat;
import static java.lang.Integer.parseInt;
import static java.lang.Long.parseLong;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

public class HudiPageSource
        implements ConnectorPageSource
{
    private final boolean hasPrefilledBlocks;
    private final Block[] prefilledBlocks;
    private final int[] delegateIndexes;
    private final ConnectorPageSource delegate;
    private final List<HudiColumnHandle> columns;
    private final List<Optional<Function<Block, Block>>> coercers;

    public HudiPageSource(
            List<HudiColumnHandle> columns,
            Map<String, String> partitionKeys,
            ConnectorPageSource delegate,
            TimeZoneKey timeZoneKey,
            TypeManager typeManager,
            Map<String, HiveType> columnCoercions)
    {
        int size = requireNonNull(columns, "columns is null").size();
        requireNonNull(partitionKeys, "partitionKeys is null");
        this.columns = requireNonNull(columns, "columns is null");
        this.delegate = requireNonNull(delegate, "delegate is null");

        prefilledBlocks = new Block[size];
        delegateIndexes = new int[size];
        ImmutableList.Builder<Optional<Function<Block, Block>>> coercersBuilder = ImmutableList.builder();

        int outputIndex = 0;
        int delegateIndex = 0;
        boolean hasPrefilledBlocks = false;
        for (HudiColumnHandle column : columns) {
            if (partitionKeys.containsKey(column.getName())) {
                String partitionValue = partitionKeys.get(column.getName());
                Type type = column.getHiveType().getType(typeManager);
                Object prefilledValue = deserializePartitionValue(type, partitionValue, column.getName(), timeZoneKey);
                prefilledBlocks[outputIndex] = Utils.nativeValueToBlock(type, prefilledValue);
                delegateIndexes[outputIndex] = -1;
                hasPrefilledBlocks = true;
            }
            else {
                delegateIndexes[outputIndex] = delegateIndex;
                delegateIndex++;
            }
            outputIndex++;

            // create type convert
            if (!columnCoercions.isEmpty() &&
                    columnCoercions.containsKey(column.getName()) &&
                    !column.getHiveType().equals(columnCoercions.get(column.getName()))) {
                coercersBuilder.add(Optional.of(HiveCoercer.createCoercer(typeManager, columnCoercions.get(column.getName()), column.getHiveType())));
            }
            else {
                coercersBuilder.add(Optional.empty());
            }
        }
        this.hasPrefilledBlocks = hasPrefilledBlocks;
        this.coercers = coercersBuilder.build();
    }

    @Override
    public long getCompletedBytes()
    {
        return delegate.getCompletedBytes();
    }

    @Override
    public long getCompletedPositions()
    {
        return delegate.getCompletedPositions();
    }

    @Override
    public long getReadTimeNanos()
    {
        return delegate.getReadTimeNanos();
    }

    @Override
    public boolean isFinished()
    {
        return delegate.isFinished();
    }

    @Override
    public Page getNextPage()
    {
        try {
            Page dataPage = delegate.getNextPage();
            if (dataPage == null) {
                return null;
            }
            if (!hasPrefilledBlocks) {
                return reAlignDataPage(dataPage, columns, coercers);
            }
            int batchSize = dataPage.getPositionCount();
            Block[] blocks = new Block[prefilledBlocks.length];
            for (int i = 0; i < prefilledBlocks.length; i++) {
                if (prefilledBlocks[i] != null) {
                    blocks[i] = new RunLengthEncodedBlock(prefilledBlocks[i], batchSize);
                }
                else {
                    Block block = dataPage.getBlock(delegateIndexes[i]);
                    Optional<Function<Block, Block>> coercer = coercers.isEmpty() ? Optional.empty() : coercers.get(i);
                    blocks[i] = reAlignDataBlock(batchSize, block, coercer);
                }
            }
            return new Page(batchSize, blocks);
        }
        catch (RuntimeException e) {
            closeWithSuppression(e);
            throwIfInstanceOf(e, PrestoException.class);
            throw new PrestoException(HUDI_CURSOR_ERROR, e);
        }
    }

    @Override
    public void close()
    {
        try {
            delegate.close();
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String toString()
    {
        return delegate.toString();
    }

    @Override
    public long getSystemMemoryUsage()
    {
        return delegate.getSystemMemoryUsage();
    }

    protected void closeWithSuppression(Throwable throwable)
    {
        requireNonNull(throwable, "throwable is null");
        try {
            close();
        }
        catch (RuntimeException e) {
            // Self-suppression not permitted
            if (throwable != e) {
                throwable.addSuppressed(e);
            }
        }
    }

    static Object deserializePartitionValue(Type type, String valueString, String name, TimeZoneKey timeZoneKey)
    {
        if (valueString == null || HIVE_DEFAULT_DYNAMIC_PARTITION.equals(valueString)) {
            return null;
        }

        try {
            if (type.equals(BOOLEAN)) {
                if (valueString.equalsIgnoreCase("true")) {
                    return true;
                }
                if (valueString.equalsIgnoreCase("false")) {
                    return false;
                }
                throw new IllegalArgumentException();
            }
            if (type.equals(INTEGER)) {
                return (long) parseInt(valueString);
            }
            if (type.equals(BIGINT)) {
                return parseLong(valueString);
            }
            if (type.equals(REAL)) {
                return (long) floatToRawIntBits(parseFloat(valueString));
            }
            if (type.equals(DOUBLE)) {
                return parseDouble(valueString);
            }
            if (type.equals(DATE)) {
                return LocalDate.parse(valueString, DateTimeFormatter.ISO_LOCAL_DATE).toEpochDay();
            }
            if (type instanceof VarcharType) {
                return utf8Slice(valueString);
            }
            if (type.equals(VarbinaryType.VARBINARY)) {
                return utf8Slice(valueString);
            }
            if (isShortDecimal(type) || isLongDecimal(type)) {
                DecimalType decimalType = (DecimalType) type;
                BigDecimal decimal = new BigDecimal(valueString);
                decimal = decimal.setScale(decimalType.getScale(), BigDecimal.ROUND_UNNECESSARY);
                if (decimal.precision() > decimalType.getPrecision()) {
                    throw new IllegalArgumentException();
                }
                BigInteger unscaledValue = decimal.unscaledValue();
                return isShortDecimal(type) ? unscaledValue.longValue() : Decimals.encodeUnscaledValue(unscaledValue);
            }
        }
        catch (IllegalArgumentException e) {
            throw new PrestoException(HUDI_INVALID_PARTITION_VALUE, format(
                    "Invalid partition value '%s' for %s partition key: %s",
                    valueString,
                    type.getDisplayName(),
                    name));
        }
        // Hudi tables don't partition by non-primitive-type columns.
        throw new PrestoException(NOT_SUPPORTED, "Invalid partition type " + type);
    }

    private static Page reAlignDataPage(
            Page dataPage,
            List<HudiColumnHandle> columns,
            List<Optional<Function<Block, Block>>> coercers)
    {
        int batchSize = dataPage.getPositionCount();
        List<Block> blocks = new ArrayList<>();
        for (int fieldId = 0; fieldId < columns.size(); fieldId++) {
            Block block = dataPage.getBlock(fieldId);
            Optional<Function<Block, Block>> coercer = coercers.isEmpty() ? Optional.empty() : coercers.get(fieldId);
            if (coercer.isPresent()) {
                block = new LazyBlock(batchSize, new CoercionLazyBlockLoader(block, coercer.get()));
            }
            blocks.add(block);
        }
        return new Page(batchSize, blocks.toArray(new Block[0]));
    }

    private static Block reAlignDataBlock(int batchSize, Block dataBlock, Optional<Function<Block, Block>> coercer)
    {
        if (coercer.isPresent()) {
            return new LazyBlock(batchSize, new CoercionLazyBlockLoader(dataBlock, coercer.get()));
        }
        return dataBlock;
    }

    private static final class CoercionLazyBlockLoader
            implements LazyBlockLoader<LazyBlock>
    {
        private final Function<Block, Block> coercer;
        private Block block;

        public CoercionLazyBlockLoader(Block block, Function<Block, Block> coercer)
        {
            this.block = requireNonNull(block, "block is null");
            this.coercer = requireNonNull(coercer, "coercer is null");
        }

        @Override
        public void load(LazyBlock lazyBlock)
        {
            if (block == null) {
                return;
            }

            lazyBlock.setBlock(coercer.apply(block.getLoadedBlock()));

            // clear reference to loader to free resources, since load was successful
            block = null;
        }
    }
}
