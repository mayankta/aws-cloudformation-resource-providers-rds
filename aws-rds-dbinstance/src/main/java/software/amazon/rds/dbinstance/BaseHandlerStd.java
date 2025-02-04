package software.amazon.rds.dbinstance;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import com.amazonaws.util.CollectionUtils;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeSecurityGroupsResponse;
import software.amazon.awssdk.services.ec2.model.SecurityGroup;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DBInstance;
import software.amazon.awssdk.services.rds.model.DbInstanceAlreadyExistsException;
import software.amazon.awssdk.services.rds.model.DbInstanceAutomatedBackupQuotaExceededException;
import software.amazon.awssdk.services.rds.model.DbInstanceNotFoundException;
import software.amazon.awssdk.services.rds.model.DbInstanceRoleAlreadyExistsException;
import software.amazon.awssdk.services.rds.model.DbInstanceRoleNotFoundException;
import software.amazon.awssdk.services.rds.model.DbParameterGroupNotFoundException;
import software.amazon.awssdk.services.rds.model.DbSecurityGroupNotFoundException;
import software.amazon.awssdk.services.rds.model.DbSnapshotAlreadyExistsException;
import software.amazon.awssdk.services.rds.model.DbSubnetGroupNotFoundException;
import software.amazon.awssdk.services.rds.model.DbUpgradeDependencyFailureException;
import software.amazon.awssdk.services.rds.model.DescribeDbInstancesResponse;
import software.amazon.awssdk.services.rds.model.InstanceQuotaExceededException;
import software.amazon.awssdk.services.rds.model.InsufficientDbInstanceCapacityException;
import software.amazon.awssdk.services.rds.model.InvalidDbClusterStateException;
import software.amazon.awssdk.services.rds.model.InvalidDbInstanceStateException;
import software.amazon.awssdk.services.rds.model.InvalidDbSecurityGroupStateException;
import software.amazon.awssdk.services.rds.model.ProvisionedIopsNotAvailableInAzException;
import software.amazon.awssdk.services.rds.model.SnapshotQuotaExceededException;
import software.amazon.awssdk.services.rds.model.StorageQuotaExceededException;
import software.amazon.awssdk.utils.StringUtils;
import software.amazon.cloudformation.exceptions.CfnNotFoundException;
import software.amazon.cloudformation.exceptions.CfnNotStabilizedException;
import software.amazon.cloudformation.proxy.AmazonWebServicesClientProxy;
import software.amazon.cloudformation.proxy.HandlerErrorCode;
import software.amazon.cloudformation.proxy.Logger;
import software.amazon.cloudformation.proxy.ProgressEvent;
import software.amazon.cloudformation.proxy.ProxyClient;
import software.amazon.cloudformation.proxy.ResourceHandlerRequest;
import software.amazon.rds.dbinstance.util.ProgressEventLambda;
import software.amazon.rds.dbinstance.util.VoidBiFunction;

public abstract class BaseHandlerStd extends BaseHandler<CallbackContext> {

    public static final String RESOURCE_IDENTIFIER = "dbinstance";
    public static final String STACK_NAME = "rds";

    protected static final int RESOURCE_ID_MAX_LENGTH = 63;

    protected static final String DB_INSTANCE_FAILED_TO_STABILIZE = "DBInstance %s failed to stabilize.";
    protected static final String DB_INSTANCE_ROLE_FAILED_TO_STABILIZE = "DBInstance %s role failed to stabilize.";

    protected static final BiFunction<ResourceModel, ProxyClient<RdsClient>, ResourceModel> NOOP_CALL = (model, proxyClient) -> model;

    protected HandlerConfig config;

    public BaseHandlerStd(final HandlerConfig config) {
        super();
        this.config = config;
    }

    protected abstract ProgressEvent<ResourceModel, CallbackContext> handleRequest(
            final AmazonWebServicesClientProxy proxy,
            final ResourceHandlerRequest<ResourceModel> request,
            final CallbackContext context,
            final ProxyClient<RdsClient> rdsProxyClient,
            final ProxyClient<Ec2Client> ec2ProxyClient,
            final Logger logger);

    @Override
    public final ProgressEvent<ResourceModel, CallbackContext> handleRequest(
            final AmazonWebServicesClientProxy proxy,
            final ResourceHandlerRequest<ResourceModel> request,
            final CallbackContext context,
            final Logger logger) {
        return handleRequest(
                proxy,
                request,
                context != null ? context : new CallbackContext(),
                proxy.newProxy(RdsClientBuilder::getClient),
                proxy.newProxy(Ec2ClientBuilder::getClient),
                logger);
    }

    protected ProgressEvent<ResourceModel, CallbackContext> waitForDbInstanceAvailableStatus(
            final AmazonWebServicesClientProxy proxy,
            final ProxyClient<RdsClient> rdsProxyClient,
            final ProgressEvent<ResourceModel, CallbackContext> progress
    ) {
        return proxy.initiate(
                "rds::stabilize-db-instance-" + getClass().getSimpleName(),
                rdsProxyClient,
                progress.getResourceModel(),
                progress.getCallbackContext()
        )
                .translateToServiceRequest(Function.identity())
                .backoffDelay(config.getBackoff())
                .makeServiceCall(NOOP_CALL)
                .stabilize((request, response, proxyInvocation, model, context) -> isDbInstanceStabilized(proxyInvocation, model))
                .progress();
    }

    protected boolean withProbing(
            final CallbackContext context,
            final String probeName,
            final int nProbes,
            final Supplier<Boolean> checker
    ) {
        final boolean check = checker.get();
        if (!config.isProbingEnabled()) {
            return check;
        }
        if (!check) {
            context.flushProbes(probeName);
            return false;
        }
        context.incProbes(probeName);
        if (context.getProbes(probeName) >= nProbes) {
            context.flushProbes(probeName);
            return true;
        }
        return false;
    }

    protected boolean isReadReplica(final ResourceModel model) {
        return StringUtils.isNotBlank(model.getSourceDBInstanceIdentifier());
    }

    protected boolean isRestoreFromSnapshot(final ResourceModel model) {
        return StringUtils.isNotBlank(model.getDBSnapshotIdentifier());
    }

    protected DBInstance fetchDBInstance(
            final ProxyClient<RdsClient> rdsProxyClient,
            final ResourceModel model
    ) {
        final DescribeDbInstancesResponse response = rdsProxyClient.injectCredentialsAndInvokeV2(
                Translator.describeDbInstancesRequest(model),
                rdsProxyClient.client()::describeDBInstances
        );
        return response.dbInstances().stream().findFirst().get();
    }

    protected SecurityGroup fetchSecurityGroup(
            final ProxyClient<Ec2Client> ec2ProxyClient,
            final String vpcId,
            final String groupName
    ) {
        final DescribeSecurityGroupsResponse response = ec2ProxyClient.injectCredentialsAndInvokeV2(
                Translator.describeSecurityGroupsRequest(vpcId, groupName),
                ec2ProxyClient.client()::describeSecurityGroups
        );
        return Optional.ofNullable(response.securityGroups())
                .orElse(Collections.emptyList())
                .stream()
                .findFirst()
                .orElse(null);
    }

    protected boolean isDbInstanceDeleted(
            final ProxyClient<RdsClient> rdsProxyClient,
            final ResourceModel model
    ) {
        try {
            fetchDBInstance(rdsProxyClient, model);
        } catch (DbInstanceNotFoundException e) {
            // the instance is gone, exactly what we need
            return true;
        }
        return false;
    }

    protected boolean isDbInstanceStabilized(
            final ProxyClient<RdsClient> rdsProxyClient,
            final ResourceModel model
    ) {
        try {
            final DBInstance dbInstance = fetchDBInstance(rdsProxyClient, model);
            return DBInstanceStatus.Available.equalsString(dbInstance.dbInstanceStatus());
        } catch (DbInstanceNotFoundException e) {
            throw new CfnNotFoundException(ResourceModel.TYPE_NAME, e.getMessage());
        } catch (Exception e) {
            throw new CfnNotStabilizedException(DB_INSTANCE_FAILED_TO_STABILIZE, model.getDBInstanceIdentifier(), e);
        }
    }

    protected boolean isDbInstanceRoleStabilized(
            final ProxyClient<RdsClient> rdsProxyClient,
            final ResourceModel model,
            final Function<Stream<software.amazon.awssdk.services.rds.model.DBInstanceRole>, Boolean> predicate
    ) {
        try {
            final DBInstance dbInstance = fetchDBInstance(rdsProxyClient, model);
            return predicate.apply(Optional.ofNullable(
                    dbInstance.associatedRoles()
            ).orElse(Collections.emptyList()).stream());
        } catch (DbInstanceNotFoundException e) {
            throw new CfnNotFoundException(ResourceModel.TYPE_NAME, e.getMessage());
        } catch (Exception e) {
            throw new CfnNotStabilizedException(DB_INSTANCE_ROLE_FAILED_TO_STABILIZE, model.getDBInstanceIdentifier(), e);
        }
    }

    protected boolean isDbInstanceRoleAdditionStabilized(
            final ProxyClient<RdsClient> rdsProxyClient,
            final ResourceModel model,
            final DBInstanceRole lookupRole
    ) {
        return isDbInstanceRoleStabilized(
                rdsProxyClient,
                model,
                (roles) -> roles.anyMatch(role -> role.roleArn().equals(lookupRole.getRoleArn()) &&
                        (role.featureName() == null || role.featureName().equals(lookupRole.getFeatureName())))
        );
    }

    protected boolean isDbInstanceRoleRemovalStabilized(
            final ProxyClient<RdsClient> rdsProxyClient,
            final ResourceModel model,
            final DBInstanceRole lookupRole
    ) {
        return isDbInstanceRoleStabilized(
                rdsProxyClient,
                model,
                (roles) -> roles.noneMatch(role -> role.roleArn().equals(lookupRole.getRoleArn()))
        );
    }

    protected void addNewTags(
            final ProxyClient<RdsClient> rdsProxyClient,
            final String arn,
            final Collection<Tag> tagsToAdd
    ) {
        if (CollectionUtils.isNullOrEmpty(tagsToAdd)) {
            return;
        }
        rdsProxyClient.injectCredentialsAndInvokeV2(
                Translator.addTagsToResourceRequest(arn, tagsToAdd),
                rdsProxyClient.client()::addTagsToResource
        );
    }

    protected void removeOldTags(
            final ProxyClient<RdsClient> rdsProxyClient,
            final String arn,
            final Collection<Tag> tagsToRemove
    ) {
        if (CollectionUtils.isNullOrEmpty(tagsToRemove)) {
            return;
        }
        rdsProxyClient.injectCredentialsAndInvokeV2(
                Translator.removeTagsFromResourceRequest(arn, tagsToRemove),
                rdsProxyClient.client()::removeTagsFromResource
        );
    }

    protected ProgressEvent<ResourceModel, CallbackContext> updateAssociatedRoles(
            final AmazonWebServicesClientProxy proxy,
            final ProxyClient<RdsClient> rdsProxyClient,
            ProgressEvent<ResourceModel, CallbackContext> progress,
            Collection<DBInstanceRole> previousRoles,
            Collection<DBInstanceRole> desiredRoles
    ) {
        final Set<DBInstanceRole> rolesToRemove = new HashSet<>(Optional.ofNullable(previousRoles).orElse(Collections.emptyList()));
        final Set<DBInstanceRole> rolesToAdd = new HashSet<>(Optional.ofNullable(desiredRoles).orElse(Collections.emptyList()));

        rolesToAdd.removeAll(Optional.ofNullable(previousRoles).orElse(Collections.emptyList()));
        rolesToRemove.removeAll(Optional.ofNullable(desiredRoles).orElse(Collections.emptyList()));

        return progress
                .then(p -> removeOldRoles(proxy, rdsProxyClient, p, rolesToRemove))
                .then(p -> addNewRoles(proxy, rdsProxyClient, p, rolesToAdd));
    }

    protected ProgressEvent<ResourceModel, CallbackContext> addNewRoles(
            final AmazonWebServicesClientProxy proxy,
            final ProxyClient<RdsClient> rdsProxyClient,
            final ProgressEvent<ResourceModel, CallbackContext> progress,
            final Collection<DBInstanceRole> rolesToAdd
    ) {
        for (final DBInstanceRole role : rolesToAdd) {
            final ProgressEvent<ResourceModel, CallbackContext> progressEvent = proxy.initiate("rds::add-roles-to-db-instance", rdsProxyClient, progress.getResourceModel(), progress.getCallbackContext())
                    .translateToServiceRequest(addRequest -> Translator.addRoleToDbInstanceRequest(progress.getResourceModel(), role))
                    .backoffDelay(config.getBackoff())
                    .makeServiceCall((request, proxyInvocation) -> {
                        return proxyInvocation.injectCredentialsAndInvokeV2(request, proxyInvocation.client()::addRoleToDBInstance);
                    })
                    .stabilize((request, response, proxyInvocation, modelRequest, callbackContext) -> isDbInstanceRoleAdditionStabilized(
                            proxyInvocation, modelRequest, role
                    ))
                    .handleError((request, exception, proxyInvocation, resourceModel, context) -> handleException(
                            ProgressEvent.progress(resourceModel, context),
                            exception
                    ))
                    .success();
            if (!progressEvent.isSuccess()) {
                return progressEvent;
            }
        }
        return ProgressEvent.progress(progress.getResourceModel(), progress.getCallbackContext());
    }

    protected ProgressEvent<ResourceModel, CallbackContext> removeOldRoles(
            final AmazonWebServicesClientProxy proxy,
            final ProxyClient<RdsClient> rdsProxyClient,
            final ProgressEvent<ResourceModel, CallbackContext> progress,
            final Collection<DBInstanceRole> rolesToRemove
    ) {
        for (final DBInstanceRole role : rolesToRemove) {
            final ProgressEvent<ResourceModel, CallbackContext> progressEvent = proxy.initiate("rds::remove-roles-from-db-instance", rdsProxyClient, progress.getResourceModel(), progress.getCallbackContext())
                    .translateToServiceRequest(removeRequest -> Translator.removeRoleFromDbInstanceRequest(
                            progress.getResourceModel(), role
                    ))
                    .backoffDelay(config.getBackoff())
                    .makeServiceCall((request, proxyInvocation) -> proxyInvocation.injectCredentialsAndInvokeV2(
                            request, proxyInvocation.client()::removeRoleFromDBInstance
                    ))
                    .stabilize((request, response, proxyInvocation, modelRequest, callbackContext) -> isDbInstanceRoleRemovalStabilized(
                            proxyInvocation, modelRequest, role
                    ))
                    .handleError((request, exception, proxyInvocation, resourceModel, context) -> handleException(
                            ProgressEvent.progress(resourceModel, context),
                            exception
                    ))
                    .success();
            if (!progressEvent.isSuccess()) {
                return progressEvent;
            }
        }
        return ProgressEvent.progress(progress.getResourceModel(), progress.getCallbackContext());
    }

    protected ProgressEvent<ResourceModel, CallbackContext> reboot(
            final AmazonWebServicesClientProxy proxy,
            final ProxyClient<RdsClient> rdsProxyClient,
            final ProgressEvent<ResourceModel, CallbackContext> progress
    ) {
        return proxy.initiate(
                "rds::reboot-db-instance",
                rdsProxyClient,
                progress.getResourceModel(),
                progress.getCallbackContext()
        ).translateToServiceRequest(Translator::rebootDbInstanceRequest)
                .backoffDelay(config.getBackoff())
                .makeServiceCall((rebootRequest, proxyInvocation) -> proxyInvocation.injectCredentialsAndInvokeV2(
                        rebootRequest,
                        proxyInvocation.client()::rebootDBInstance
                ))
                .handleError((request, exception, client, model, context) -> handleException(
                        ProgressEvent.progress(model, context),
                        exception
                ))
                .progress();
    }

    protected ProgressEvent<ResourceModel, CallbackContext> rebootAwait(
            final AmazonWebServicesClientProxy proxy,
            final ProxyClient<RdsClient> rdsProxyClient,
            final ProgressEvent<ResourceModel, CallbackContext> progress
    ) {
        return reboot(proxy, rdsProxyClient, progress).then(p -> waitForDbInstanceAvailableStatus(proxy, rdsProxyClient, p));
    }

    protected ProgressEvent<ResourceModel, CallbackContext> ensureEngineSet(
            final ProxyClient<RdsClient> rdsProxyClient,
            final ProgressEvent<ResourceModel, CallbackContext> progress
    ) {
        final ResourceModel model = progress.getResourceModel();
        if (StringUtils.isEmpty(model.getEngine())) {
            if (isRestoreFromSnapshot(model)) {
                try {
                    final DBInstance dbInstance = fetchDBInstance(rdsProxyClient, model);
                    model.setEngine(dbInstance.engine());
                } catch (Exception e) {
                    return handleException(progress, e);
                }
            }
        }
        return progress;
    }

    protected ProgressEvent<ResourceModel, CallbackContext> handleException(
            final ProgressEvent<ResourceModel, CallbackContext> progress,
            final Exception exception
    ) {
        final ResourceModel model = progress.getResourceModel();
        final CallbackContext context = progress.getCallbackContext();
        if (exception instanceof DbInstanceNotFoundException ||
                exception instanceof DbParameterGroupNotFoundException ||
                exception instanceof DbSecurityGroupNotFoundException ||
                exception instanceof DbSubnetGroupNotFoundException
        ) {
            return ProgressEvent.failed(model, context, HandlerErrorCode.NotFound, exception.getMessage());
        } else if (exception instanceof DbInstanceAutomatedBackupQuotaExceededException ||
                exception instanceof InsufficientDbInstanceCapacityException ||
                exception instanceof InstanceQuotaExceededException ||
                exception instanceof SnapshotQuotaExceededException ||
                exception instanceof StorageQuotaExceededException
        ) {
            return ProgressEvent.failed(model, context, HandlerErrorCode.ServiceLimitExceeded, exception.getMessage());
        } else if (exception instanceof InvalidDbInstanceStateException ||
                exception instanceof InvalidDbClusterStateException ||
                exception instanceof DbSnapshotAlreadyExistsException ||
                exception instanceof DbUpgradeDependencyFailureException ||
                exception instanceof InvalidDbSecurityGroupStateException
        ) {
            return ProgressEvent.failed(model, context, HandlerErrorCode.ResourceConflict, exception.getMessage());
        } else if (exception instanceof ProvisionedIopsNotAvailableInAzException) {
            return ProgressEvent.failed(model, context, HandlerErrorCode.InvalidRequest, exception.getMessage());
        } else if (exception instanceof DbInstanceAlreadyExistsException) {
            return ProgressEvent.failed(model, context, HandlerErrorCode.AlreadyExists, exception.getMessage());
        } else if (exception instanceof DbInstanceRoleAlreadyExistsException ||
                exception instanceof DbInstanceRoleNotFoundException
        ) {
            return ProgressEvent.progress(model, context);
        } else if (exception instanceof SdkClientException ||
                exception instanceof com.amazonaws.SdkClientException
        ) {
            return ProgressEvent.failed(model, context, HandlerErrorCode.ServiceInternalError, exception.getMessage());
        }

        return ProgressEvent.failed(model, context, HandlerErrorCode.InternalFailure, exception.getMessage());
    }

    protected <K, V> Map<K, V> mergeMaps(Collection<Map<K, V>> maps) {
        final Map<K, V> result = new HashMap<>();
        for (Map<K, V> map : maps) {
            if (map != null) {
                result.putAll(map);
            }
        }
        return result;
    }

    protected ProgressEvent<ResourceModel, CallbackContext> execOnce(
            final ProgressEvent<ResourceModel, CallbackContext> progress,
            final ProgressEventLambda func,
            final Function<CallbackContext, Boolean> conditionGetter,
            final VoidBiFunction<CallbackContext, Boolean> conditionSetter
    ) {
        if (!conditionGetter.apply(progress.getCallbackContext())) {
            return func.enact().then(p -> {
                conditionSetter.apply(p.getCallbackContext(), true);
                return p;
            });
        }
        return progress;
    }
}
