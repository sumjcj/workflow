package com.nirmata.workflow.details;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.nirmata.workflow.WorkflowManager;
import com.nirmata.workflow.details.internalmodels.RunnableTask;
import com.nirmata.workflow.executor.TaskExecution;
import com.nirmata.workflow.executor.TaskExecutionStatus;
import com.nirmata.workflow.models.TaskExecutionResult;
import com.nirmata.workflow.executor.TaskExecutor;
import com.nirmata.workflow.models.ExecutableTask;
import com.nirmata.workflow.models.RunId;
import com.nirmata.workflow.models.Task;
import com.nirmata.workflow.models.TaskId;
import com.nirmata.workflow.queue.QueueConsumer;
import com.nirmata.workflow.queue.QueueFactory;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.utils.CloseableUtils;
import org.apache.curator.utils.EnsurePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

public class WorkflowManagerImpl implements WorkflowManager
{
    private final Logger log = LoggerFactory.getLogger(getClass());
    private final CuratorFramework curator;
    private final String instanceName;
    private final List<QueueConsumer> consumers;
    private final SchedulerSelector schedulerSelector;
    private final EnsurePath ensureRunPath;
    private final EnsurePath ensureCompletedTaskPath;
    private final AtomicReference<State> state = new AtomicReference<>(State.LATENT);

    private enum State
    {
        LATENT,
        STARTED,
        CLOSED
    }

    public WorkflowManagerImpl(CuratorFramework curator, QueueFactory queueFactory, String instanceName, List<TaskExecutorSpec> specs)
    {
        this.curator = Preconditions.checkNotNull(curator, "curator cannot be null");
        queueFactory = Preconditions.checkNotNull(queueFactory, "queueFactory cannot be null");
        this.instanceName = Preconditions.checkNotNull(instanceName, "instanceName cannot be null");
        specs = Preconditions.checkNotNull(specs, "specs cannot be null");

        ensureRunPath = curator.newNamespaceAwareEnsurePath(ZooKeeperConstants.getRunParentPath());
        ensureCompletedTaskPath = curator.newNamespaceAwareEnsurePath(ZooKeeperConstants.getCompletedTaskParentPath());

        consumers = makeTaskConsumers(queueFactory, specs);
        schedulerSelector = new SchedulerSelector(this, queueFactory, specs);
    }

    public CuratorFramework getCurator()
    {
        return curator;
    }

    @Override
    public void start()
    {
        Preconditions.checkState(state.compareAndSet(State.LATENT, State.STARTED), "Already started");

        consumers.forEach(QueueConsumer::start);
        schedulerSelector.start();
    }

    @Override
    public RunId submitTask(Task task)
    {
        Preconditions.checkState(state.get() == State.STARTED, "Not started");
        return submitSubTask(task, null, null);
    }

    @Override
    public RunId submitSubTask(Task task, RunId mainRunId, TaskId mainTaskId)
    {
        // TODO handle mainRunId and mainTaskId

        try
        {
            ensureRunPath.ensure(curator.getZookeeperClient());
            ensureCompletedTaskPath.ensure(curator.getZookeeperClient());
        }
        catch ( Exception e )
        {
            throw new RuntimeException(e);
        }

        Preconditions.checkState(state.get() == State.STARTED, "Not started");

        RunnableTaskDagBuilder builder = new RunnableTaskDagBuilder(task);
        RunnableTask runnableTask = new RunnableTask(builder.getTasks(), builder.getEntries(), LocalDateTime.now(), null);

        TaskExecutionResult taskExecutionResult = new TaskExecutionResult(TaskExecutionStatus.SUCCESS, "");
        byte[] runnableTaskJson = JsonSerializer.toBytes(JsonSerializer.newRunnableTask(runnableTask));
        byte[] taskExecutionResultJson = JsonSerializer.toBytes(JsonSerializer.newTaskExecutionResult(taskExecutionResult));

        RunId runId = new RunId();
        String runPath = ZooKeeperConstants.getRunPath(runId);
        String completedTaskPath = ZooKeeperConstants.getCompletedTaskPath(runId, new TaskId(""));
        try
        {
            curator.inTransaction()
                .create().forPath(runPath, runnableTaskJson)
            .and()
                .create().forPath(completedTaskPath, taskExecutionResultJson)   // a fake completed task to kick-off task creation
            .and()
                .commit();
        }
        catch ( Exception e )
        {
            throw new RuntimeException(e);
        }

        return runId;
    }

    @Override
    public void cancelRun(RunId runId, String message)
    {
        // TODO
    }

    @Override
    public Map<String, String> getTaskData(RunId runId, TaskId taskId)
    {
        return null;
    }

    public String getInstanceName()
    {
        return instanceName;
    }

    @Override
    public void close() throws IOException
    {
        if ( state.compareAndSet(State.STARTED, State.CLOSED) )
        {
            consumers.forEach(CloseableUtils::closeQuietly);
            CloseableUtils.closeQuietly(schedulerSelector);
        }
    }

    private void excecuteTask(TaskExecutor taskExecutor, ExecutableTask executableTask)
    {
        log.info("Executing task: " + executableTask);
        TaskExecution taskExecution = null;// TODO taskExecutor.newTaskExecution(executableTask);

        TaskExecutionResult result = taskExecution.execute();
        String json = "";// TODO nodeToString(newTaskExecutionResult(result));
        try
        {
            String path = "";// TODO ZooKeeperConstants.getCompletedTaskPath(executableTask.getRunId(), executableTask.getTask().getTaskId());
            curator.create().creatingParentsIfNeeded().forPath(path, json.getBytes());
        }
        catch ( Exception e )
        {
            log.error("Could not set completed data for executable task: " + executableTask, e);
            throw new RuntimeException(e);
        }
    }

    private List<QueueConsumer> makeTaskConsumers(QueueFactory queueFactory, List<TaskExecutorSpec> specs)
    {
        ImmutableList.Builder<QueueConsumer> builder = ImmutableList.builder();
        specs.forEach(spec -> {
            IntStream.range(0, spec.getQty()).forEach(i -> {
                QueueConsumer consumer = queueFactory.createQueueConsumer(this, t -> excecuteTask(spec.getTaskExecutor(), t), null);
                builder.add(consumer);
            });
        });

        return builder.build();
    }
}