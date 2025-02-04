package software.amazon.rds.dbinstance;

import java.util.Optional;

import org.apache.commons.lang3.BooleanUtils;

import com.amazonaws.util.StringUtils;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DeleteDbInstanceResponse;
import software.amazon.cloudformation.proxy.AmazonWebServicesClientProxy;
import software.amazon.cloudformation.proxy.Logger;
import software.amazon.cloudformation.proxy.ProgressEvent;
import software.amazon.cloudformation.proxy.ProxyClient;
import software.amazon.cloudformation.proxy.ResourceHandlerRequest;
import software.amazon.cloudformation.resource.IdentifierUtils;

public class DeleteHandler extends BaseHandlerStd {

    private static final String SNAPSHOT_PREFIX = "Snapshot-";
    private static final int SNAPSHOT_MAX_LENGTH = 255;

    public DeleteHandler() {
        this(new HandlerConfig());
    }

    public DeleteHandler(final HandlerConfig config) {
        super(config);
    }

    protected ProgressEvent<ResourceModel, CallbackContext> handleRequest(
            final AmazonWebServicesClientProxy proxy,
            final ResourceHandlerRequest<ResourceModel> request,
            final CallbackContext callbackContext,
            final ProxyClient<RdsClient> rdsProxyClient,
            final ProxyClient<Ec2Client> ec2ProxyClient,
            final Logger logger
    ) {
        final ResourceModel resourceModel = request.getDesiredResourceState();
        String snapshotIdentifier = null;
        // https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/aws-attribute-deletionpolicy.html
        // For AWS::RDS::DBInstance resources that don't specify the DBClusterIdentifier property, the default policy is Snapshot.
        if (BooleanUtils.isNotFalse(request.getSnapshotRequested())) {
            snapshotIdentifier = resourceModel.getDBSnapshotIdentifier();
            if (StringUtils.isNullOrEmpty(snapshotIdentifier)) {
                snapshotIdentifier = IdentifierUtils.generateResourceIdentifier(
                        Optional.ofNullable(request.getStackId()).orElse(STACK_NAME),
                        SNAPSHOT_PREFIX + Optional.ofNullable(request.getLogicalResourceIdentifier()).orElse(RESOURCE_IDENTIFIER),
                        request.getClientRequestToken(),
                        SNAPSHOT_MAX_LENGTH
                );
            }
        }
        final String finalSnapshotIdentifier = snapshotIdentifier;

        return proxy.initiate("rds::delete-db-instance", rdsProxyClient, request.getDesiredResourceState(), callbackContext)
                .translateToServiceRequest(model -> Translator.deleteDbInstanceRequest(model, finalSnapshotIdentifier))
                .backoffDelay(config.getBackoff())
                .makeServiceCall((deleteRequest, proxyInvocation) -> {
                    if (callbackContext.isDeleted()) {
                        return callbackContext.response("rds::delete-db-instance");
                    }
                    final DeleteDbInstanceResponse response = proxyInvocation.injectCredentialsAndInvokeV2(
                            deleteRequest,
                            proxyInvocation.client()::deleteDBInstance
                    );
                    callbackContext.setDeleted(true);
                    return response;
                })
                .stabilize((deleteRequest, deleteResponse, proxyInvocation, model, context) -> isDbInstanceDeleted(proxyInvocation, model))
                .handleError((deleteRequest, exception, client, model, context) -> handleException(
                        ProgressEvent.progress(model, context),
                        exception
                ))
                .done((deleteRequest, deleteResponse, proxyInvocation, model, context) -> ProgressEvent.defaultSuccessHandler(null));
    }
}
