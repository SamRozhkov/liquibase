package liquibase.changelog.visitor;

import liquibase.ChecksumVersions;
import liquibase.Scope;
import liquibase.change.CheckSum;
import liquibase.changelog.ChangeLogHistoryService;
import liquibase.changelog.ChangeLogHistoryServiceFactory;
import liquibase.changelog.ChangeSet;
import liquibase.changelog.ChangeSet.ExecType;
import liquibase.changelog.ChangeSet.RunStatus;
import liquibase.changelog.DatabaseChangeLog;
import liquibase.changelog.filter.ChangeSetFilterResult;
import liquibase.changelog.filter.ShouldRunChangeSetFilter;
import liquibase.database.Database;
import liquibase.database.ObjectQuotingStrategy;
import liquibase.exception.DatabaseException;
import liquibase.exception.DatabaseHistoryException;
import liquibase.exception.LiquibaseException;
import liquibase.exception.MigrationFailedException;
import liquibase.executor.Executor;
import liquibase.executor.ExecutorService;
import liquibase.executor.LoggingExecutor;
import liquibase.statement.core.UpdateChangeSetChecksumStatement;

import java.util.Objects;
import java.util.Set;

public class UpdateVisitor implements ChangeSetVisitor {

    private final Database database;

    private ChangeExecListener execListener;

    private ShouldRunChangeSetFilter shouldRunChangeSetFilter;

    /**
     * @deprecated - please use the constructor with ChangeExecListener, which can be null.
     */
    @Deprecated
    public UpdateVisitor(Database database) {
        this.database = database;
    }

    @Deprecated
    public UpdateVisitor(Database database, ChangeExecListener execListener) {
        this(database);
              this.execListener = execListener;
    }

    public UpdateVisitor(Database database, ChangeExecListener execListener, ShouldRunChangeSetFilter shouldRunChangeSetFilter) {
        this(database);
        this.execListener = execListener;
        this.shouldRunChangeSetFilter = shouldRunChangeSetFilter;
    }

    @Override
    public Direction getDirection() {
        return ChangeSetVisitor.Direction.FORWARD;
    }

    @Override
    public void visit(ChangeSet changeSet, DatabaseChangeLog databaseChangeLog, Database database,
                      Set<ChangeSetFilterResult> filterResults) throws LiquibaseException {
        logMdcData(changeSet);

        // if we don't have shouldRunChangeSetFilter go on with the old behavior assuming that it has been validated before
        boolean isAccepted = this.shouldRunChangeSetFilter == null || this.shouldRunChangeSetFilter.accepts(changeSet).isAccepted();
        CheckSum oldChecksum = updateCheckSumIfRequired(changeSet);
        if (isAccepted) {
            executeAcceptedChange(changeSet, databaseChangeLog, database);
        } else if ((oldChecksum == null || oldChecksum.getVersion() < ChecksumVersions.latest().getVersion())) {
            upgradeCheckSumVersionForAlreadyExecutedOrNullChange(changeSet, database, oldChecksum);
        }

        this.database.commit();
    }

    private static CheckSum updateCheckSumIfRequired(ChangeSet changeSet) {
        CheckSum oldChecksum = null;
        if (changeSet.getStoredCheckSum() != null && changeSet.getStoredCheckSum().getVersion() < ChecksumVersions.latest().getVersion()) {
            oldChecksum = changeSet.getStoredCheckSum();
            changeSet.clearCheckSum();
            changeSet.setStoredCheckSum(changeSet.generateCheckSum(ChecksumVersions.latest()));
        }
        return oldChecksum;
    }

    private static void upgradeCheckSumVersionForAlreadyExecutedOrNullChange(ChangeSet changeSet, Database database, CheckSum oldChecksum) throws DatabaseException {
        Executor executor = Scope.getCurrentScope().getSingleton(ExecutorService.class).getExecutor("jdbc", database);
        if (! (executor instanceof LoggingExecutor)) {
            Scope.getCurrentScope().getUI().sendMessage(String.format("Upgrading checksum for Changeset %s from %s to %s.",
                    changeSet, (oldChecksum != null? oldChecksum.toString() : "<null>"), changeSet.getStoredCheckSum().toString()));
        }
        Scope.getCurrentScope().getSingleton(ExecutorService.class).getExecutor("jdbc", database)
                .execute(new UpdateChangeSetChecksumStatement(changeSet));
    }

    private void executeAcceptedChange(ChangeSet changeSet, DatabaseChangeLog databaseChangeLog, Database database)
            throws DatabaseException, DatabaseHistoryException, MigrationFailedException {
        Executor executor = Scope.getCurrentScope().getSingleton(ExecutorService.class).getExecutor("jdbc", database);
        if (!(executor instanceof LoggingExecutor)) {
            Scope.getCurrentScope().getUI().sendMessage("Running Changeset: " + changeSet);
        }
        RunStatus runStatus = this.database.getRunStatus(changeSet);
        Scope.getCurrentScope().getLog(getClass()).fine("Running Changeset: " + changeSet);
        fireWillRun(changeSet, databaseChangeLog, database, runStatus);
        ExecType execType;
        ObjectQuotingStrategy previousStr = this.database.getObjectQuotingStrategy();
        try {
            execType = changeSet.execute(databaseChangeLog, execListener, this.database);

        } catch (MigrationFailedException e) {
            fireRunFailed(changeSet, databaseChangeLog, database, e);
            throw e;
        }
        if (!Objects.equals(runStatus, RunStatus.NOT_RAN) && Objects.equals(execType, ExecType.EXECUTED)) {
            execType = ExecType.RERAN;
        }
        fireRan(changeSet, databaseChangeLog, database, execType);
        addAttributesForMdc(changeSet, execType);
        // reset object quoting strategy after running changeset
        this.database.setObjectQuotingStrategy(previousStr);
        this.database.markChangeSetExecStatus(changeSet, execType);
    }

    protected void fireRunFailed(ChangeSet changeSet, DatabaseChangeLog databaseChangeLog, Database database, MigrationFailedException e) {
        if (execListener != null) {
            execListener.runFailed(changeSet, databaseChangeLog, database, e);
        }
    }

    protected void fireWillRun(ChangeSet changeSet, DatabaseChangeLog databaseChangeLog, Database database2, RunStatus runStatus) {
      if (execListener != null) {
        execListener.willRun(changeSet, databaseChangeLog, database, runStatus);
      }      
    }

    protected void fireRan(ChangeSet changeSet, DatabaseChangeLog databaseChangeLog, Database database2, ExecType execType) {
      if (execListener != null) {
        execListener.ran(changeSet, databaseChangeLog, database, execType);
      }
    }

    private void addAttributesForMdc(ChangeSet changeSet, ExecType execType) {
        changeSet.setAttribute("updateExecType", execType);
        ChangeLogHistoryService changelogService = ChangeLogHistoryServiceFactory.getInstance().getChangeLogService(database);
        String deploymentId = changelogService.getDeploymentId();
        changeSet.setAttribute("deploymentId", deploymentId);
    }
}
