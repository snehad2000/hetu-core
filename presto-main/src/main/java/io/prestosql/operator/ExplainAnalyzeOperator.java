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
package io.prestosql.operator;

import com.google.common.collect.ImmutableList;
import io.prestosql.execution.QueryInfo;
import io.prestosql.execution.QueryPerformanceFetcher;
import io.prestosql.execution.StageId;
import io.prestosql.execution.StageInfo;
import io.prestosql.metadata.Metadata;
import io.prestosql.snapshot.SingleInputSnapshotState;
import io.prestosql.spi.Page;
import io.prestosql.spi.block.BlockBuilder;
import io.prestosql.spi.plan.PlanNodeId;
import io.prestosql.spi.snapshot.BlockEncodingSerdeProvider;
import io.prestosql.spi.snapshot.RestorableConfig;

import java.io.Serializable;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.google.common.base.Preconditions.checkState;
import static io.prestosql.spi.type.VarcharType.VARCHAR;
import static io.prestosql.sql.planner.planprinter.PlanPrinter.textDistributedPlan;
import static java.util.Objects.requireNonNull;

@RestorableConfig(uncapturedFields = {"queryPerformanceFetcher", "metadata", "finishing", "snapshotState"})
public class ExplainAnalyzeOperator
        implements Operator
{
    public static class ExplainAnalyzeOperatorFactory
            implements OperatorFactory
    {
        private final int operatorId;
        private final PlanNodeId planNodeId;
        private final QueryPerformanceFetcher queryPerformanceFetcher;
        private final Metadata metadata;
        private final boolean verbose;
        private boolean closed;

        public ExplainAnalyzeOperatorFactory(
                int operatorId,
                PlanNodeId planNodeId,
                QueryPerformanceFetcher queryPerformanceFetcher,
                Metadata metadata,
                boolean verbose)
        {
            this.operatorId = operatorId;
            this.planNodeId = requireNonNull(planNodeId, "planNodeId is null");
            this.queryPerformanceFetcher = requireNonNull(queryPerformanceFetcher, "queryPerformanceFetcher is null");
            this.metadata = requireNonNull(metadata, "metadata is null");
            this.verbose = verbose;
        }

        @Override
        public Operator createOperator(DriverContext driverContext)
        {
            checkState(!closed, "Factory is already closed");
            OperatorContext operatorContext = driverContext.addOperatorContext(operatorId, planNodeId, ExplainAnalyzeOperator.class.getSimpleName());
            return new ExplainAnalyzeOperator(operatorContext, queryPerformanceFetcher, metadata, verbose);
        }

        @Override
        public void noMoreOperators()
        {
            closed = true;
        }

        @Override
        public OperatorFactory duplicate()
        {
            return new ExplainAnalyzeOperatorFactory(operatorId, planNodeId, queryPerformanceFetcher, metadata, verbose);
        }
    }

    private final OperatorContext operatorContext;
    private final QueryPerformanceFetcher queryPerformanceFetcher;
    private final Metadata metadata;
    private final boolean verbose;
    private boolean finishing;
    private boolean outputConsumed;

    private final SingleInputSnapshotState snapshotState;

    public ExplainAnalyzeOperator(
            OperatorContext operatorContext,
            QueryPerformanceFetcher queryPerformanceFetcher,
            Metadata metadata,
            boolean verbose)
    {
        this.operatorContext = requireNonNull(operatorContext, "operatorContext is null");
        this.queryPerformanceFetcher = requireNonNull(queryPerformanceFetcher, "queryPerformanceFetcher is null");
        this.metadata = requireNonNull(metadata, "metadata is null");
        this.verbose = verbose;
        this.snapshotState = operatorContext.isSnapshotEnabled() ? SingleInputSnapshotState.forOperator(this, null) : null;
    }

    @Override
    public OperatorContext getOperatorContext()
    {
        return operatorContext;
    }

    @Override
    public void finish()
    {
        finishing = true;
    }

    @Override
    public boolean isFinished()
    {
        if (snapshotState != null && snapshotState.hasMarker()) {
            // Snapshot: there are pending markers. Need to send them out before finishing this operator.
            return false;
        }

        return finishing && outputConsumed;
    }

    @Override
    public boolean needsInput()
    {
        return !finishing;
    }

    @Override
    public void addInput(Page page)
    {
        checkState(needsInput());

        if (snapshotState != null) {
            if (snapshotState.processPage(page)) {
                return;
            }
        }
        // Ignore the input
    }

    @Override
    public Page getOutput()
    {
        if (snapshotState != null) {
            Page marker = snapshotState.nextMarker();
            if (marker != null) {
                return marker;
            }
        }

        if (!finishing) {
            return null;
        }

        QueryInfo queryInfo = queryPerformanceFetcher.getQueryInfo(operatorContext.getDriverContext().getTaskId().getQueryId());
        checkState(queryInfo.getOutputStage().isPresent(), "Output stage is missing");
        checkState(queryInfo.getOutputStage().get().getSubStages().size() == 1, "Expected one sub stage of explain node");

        if (!hasFinalStageInfo(queryInfo.getOutputStage().get())) {
            return null;
        }

        String plan = textDistributedPlan(queryInfo.getOutputStage().get().getSubStages().get(0), metadata, operatorContext.getSession(), verbose);
        BlockBuilder builder = VARCHAR.createBlockBuilder(null, 1);
        VARCHAR.writeString(builder, plan);

        outputConsumed = true;
        return new Page(builder.build());
    }

    @Override
    public Page pollMarker()
    {
        return snapshotState.nextMarker();
    }

    private boolean hasFinalStageInfo(StageInfo stageInfo)
    {
        boolean isFinalStageInfo = isFinalStageInfo(stageInfo);
        if (!isFinalStageInfo) {
            try {
                TimeUnit.MILLISECONDS.sleep(100);
            }
            catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        return isFinalStageInfo(stageInfo);
    }

    private boolean isFinalStageInfo(StageInfo stageInfo)
    {
        List<StageInfo> subStages = getSubStagesOf(operatorContext.getDriverContext().getTaskId().getStageId(), stageInfo);
        return subStages.stream().allMatch(StageInfo::isFinalStageInfo);
    }

    private static List<StageInfo> getSubStagesOf(StageId stageId, StageInfo rootStage)
    {
        ImmutableList.Builder<StageInfo> collector = ImmutableList.builder();
        getSubStages(stageId, rootStage, collector, false);
        return collector.build();
    }

    private static void getSubStages(StageId stageId, StageInfo rootStage, ImmutableList.Builder<StageInfo> collector, boolean add)
    {
        if (rootStage.getStageId().equals(stageId)) {
            add = true;
        }
        List<StageInfo> subStages = rootStage.getSubStages();
        for (StageInfo subStage : subStages) {
            getSubStages(stageId, subStage, collector, add);
        }

        if (add && !rootStage.getStageId().equals(stageId)) {
            collector.add(rootStage);
        }
    }

    @Override
    public void close()
    {
        if (snapshotState != null) {
            snapshotState.close();
        }
    }

    @Override
    public Object capture(BlockEncodingSerdeProvider serdeProvider)
    {
        ExplainAnalyzeOperatorState myState = new ExplainAnalyzeOperatorState();
        myState.operatorContext = operatorContext.capture(serdeProvider);
        myState.outputConsumed = outputConsumed;
        return myState;
    }

    @Override
    public void restore(Object state, BlockEncodingSerdeProvider serdeProvider)
    {
        ExplainAnalyzeOperatorState myState = (ExplainAnalyzeOperatorState) state;
        this.operatorContext.restore(myState.operatorContext, serdeProvider);
        this.outputConsumed = myState.outputConsumed;
    }

    private static class ExplainAnalyzeOperatorState
            implements Serializable
    {
        private Object operatorContext;
        private boolean outputConsumed;
    }
}
