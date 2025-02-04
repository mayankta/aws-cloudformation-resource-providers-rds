package software.amazon.rds.dbinstance;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Collections;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.google.common.collect.ImmutableList;
import lombok.Getter;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeSecurityGroupsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeSecurityGroupsResponse;
import software.amazon.awssdk.services.ec2.model.SecurityGroup;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.AddRoleToDbInstanceRequest;
import software.amazon.awssdk.services.rds.model.AddRoleToDbInstanceResponse;
import software.amazon.awssdk.services.rds.model.AddTagsToResourceRequest;
import software.amazon.awssdk.services.rds.model.AddTagsToResourceResponse;
import software.amazon.awssdk.services.rds.model.DBEngineVersion;
import software.amazon.awssdk.services.rds.model.DBInstance;
import software.amazon.awssdk.services.rds.model.DBParameterGroup;
import software.amazon.awssdk.services.rds.model.DBParameterGroupStatus;
import software.amazon.awssdk.services.rds.model.DBSubnetGroup;
import software.amazon.awssdk.services.rds.model.DbInstanceRoleAlreadyExistsException;
import software.amazon.awssdk.services.rds.model.DbInstanceRoleNotFoundException;
import software.amazon.awssdk.services.rds.model.DescribeDbEngineVersionsRequest;
import software.amazon.awssdk.services.rds.model.DescribeDbEngineVersionsResponse;
import software.amazon.awssdk.services.rds.model.DescribeDbInstancesRequest;
import software.amazon.awssdk.services.rds.model.DescribeDbParameterGroupsRequest;
import software.amazon.awssdk.services.rds.model.DescribeDbParameterGroupsResponse;
import software.amazon.awssdk.services.rds.model.ModifyDbInstanceRequest;
import software.amazon.awssdk.services.rds.model.ModifyDbInstanceResponse;
import software.amazon.awssdk.services.rds.model.RebootDbInstanceRequest;
import software.amazon.awssdk.services.rds.model.RebootDbInstanceResponse;
import software.amazon.awssdk.services.rds.model.RemoveRoleFromDbInstanceRequest;
import software.amazon.awssdk.services.rds.model.RemoveRoleFromDbInstanceResponse;
import software.amazon.awssdk.services.rds.model.RemoveTagsFromResourceRequest;
import software.amazon.awssdk.services.rds.model.RemoveTagsFromResourceResponse;
import software.amazon.cloudformation.proxy.AmazonWebServicesClientProxy;
import software.amazon.cloudformation.proxy.HandlerErrorCode;
import software.amazon.cloudformation.proxy.ProxyClient;
import software.amazon.cloudformation.proxy.ResourceHandlerRequest;
import software.amazon.cloudformation.proxy.delay.Constant;

@ExtendWith(MockitoExtension.class)
public class UpdateHandlerTest extends AbstractHandlerTest {

    @Mock
    @Getter
    private AmazonWebServicesClientProxy proxy;

    @Mock
    @Getter
    private ProxyClient<RdsClient> rdsProxy;

    @Mock
    @Getter
    private ProxyClient<Ec2Client> ec2Proxy;

    @Mock
    private RdsClient rdsClient;

    @Mock
    private Ec2Client ec2Client;

    @Getter
    private UpdateHandler handler;

    @BeforeEach
    public void setup() {
        handler = new UpdateHandler(
                HandlerConfig.builder()
                        .probingEnabled(false)
                        .backoff(Constant.of()
                                .delay(Duration.ofSeconds(1))
                                .timeout(Duration.ofSeconds(120))
                                .build())
                        .build()
        );
        proxy = new AmazonWebServicesClientProxy(logger, MOCK_CREDENTIALS, () -> Duration.ofSeconds(600).toMillis());
        rdsClient = mock(RdsClient.class);
        ec2Client = mock(Ec2Client.class);
        rdsProxy = MOCK_PROXY(proxy, rdsClient);
        ec2Proxy = MOCK_PROXY(proxy, ec2Client);
    }

    @AfterEach
    public void tear_down() {
        verify(rdsClient, atLeastOnce()).serviceName();
        verifyNoMoreInteractions(rdsClient);
        verifyNoMoreInteractions(ec2Client);
    }

    @Test
    public void handleRequest_InitiatesModifyRequest_InProgress() {
        final ModifyDbInstanceResponse modifyDbInstanceResponse = ModifyDbInstanceResponse.builder()
                .dbInstance(DB_INSTANCE_ACTIVE)
                .build();
        when(rdsProxy.client().modifyDBInstance(any(ModifyDbInstanceRequest.class))).thenReturn(modifyDbInstanceResponse);

        final CallbackContext context = new CallbackContext();
        context.setUpdated(false);
        context.setRebooted(true);
        context.setUpdatedRoles(true);

        test_handleRequest_base(
                context,
                () -> DB_INSTANCE_ACTIVE,
                () -> RESOURCE_MODEL_BLDR().build(),
                () -> RESOURCE_MODEL_ALTER,
                expectSuccess()
        );

        verify(rdsProxy.client(), times(2)).describeDBInstances(any(DescribeDbInstancesRequest.class));
        verify(rdsProxy.client()).modifyDBInstance(any(ModifyDbInstanceRequest.class));
    }

    @Test
    public void handleRequest_SuccessTagsAddOnly() {
        final AddTagsToResourceResponse addTagsToResourceResponse = AddTagsToResourceResponse.builder().build();
        when(rdsProxy.client().addTagsToResource(any(AddTagsToResourceRequest.class))).thenReturn(addTagsToResourceResponse);

        final CallbackContext context = new CallbackContext();
        context.setUpdated(true);
        context.setRebooted(true);
        context.setUpdatedRoles(true);

        test_handleRequest_base(
                context,
                ResourceHandlerRequest.<ResourceModel>builder()
                        .previousResourceTags(Collections.emptyMap())
                        .desiredResourceTags(Translator.translateTagsToRequest(TAG_LIST)),
                () -> DB_INSTANCE_ACTIVE,
                () -> RESOURCE_MODEL_BLDR().build(),
                () -> RESOURCE_MODEL_BLDR().build(),
                expectSuccess()
        );

        verify(rdsProxy.client()).addTagsToResource(any(AddTagsToResourceRequest.class));
        verify(rdsProxy.client(), times(2)).describeDBInstances(any(DescribeDbInstancesRequest.class));
    }

    @Test
    public void handleRequest_SuccessTagsRemoveOnly() {
        final RemoveTagsFromResourceResponse removeTagsFromResourceResponse = RemoveTagsFromResourceResponse.builder().build();
        when(rdsProxy.client().removeTagsFromResource(any(RemoveTagsFromResourceRequest.class))).thenReturn(removeTagsFromResourceResponse);

        final CallbackContext context = new CallbackContext();
        context.setUpdated(true);
        context.setRebooted(true);
        context.setUpdatedRoles(true);

        test_handleRequest_base(
                context,
                ResourceHandlerRequest.<ResourceModel>builder()
                        .previousResourceTags(Translator.translateTagsToRequest(TAG_LIST))
                        .desiredResourceTags(Translator.translateTagsToRequest(TAG_LIST_EMPTY)),
                () -> DB_INSTANCE_ACTIVE,
                () -> RESOURCE_MODEL_BLDR().build(),
                () -> RESOURCE_MODEL_BLDR().build(),
                expectSuccess()
        );

        verify(rdsProxy.client()).removeTagsFromResource(any(RemoveTagsFromResourceRequest.class));
        verify(rdsProxy.client(), times(2)).describeDBInstances(any(DescribeDbInstancesRequest.class));
    }

    @Test
    public void handleRequest_DeleteNonExistingRole() {
        // compute a complete sequence of transitions from the initial set of roles to the final one.
        final Queue<DBInstance> transitions = new ConcurrentLinkedQueue<>(
                computeAssociatedRoleTransitions(DB_INSTANCE_ACTIVE, ASSOCIATED_ROLES, ASSOCIATED_ROLES_ALTER)
        );
        // We expect describeDBInstances to be called 2 more times: for tag mutation and for the final resource fetch.
        transitions.add(DB_INSTANCE_ACTIVE.toBuilder()
                .associatedRoles(Translator.translateAssociatedRolesToSdk(ASSOCIATED_ROLES_ALTER))
                .build());
        transitions.add(DB_INSTANCE_ACTIVE.toBuilder()
                .associatedRoles(Translator.translateAssociatedRolesToSdk(ASSOCIATED_ROLES_ALTER))
                .build());

        when(rdsProxy.client().removeRoleFromDBInstance(any(RemoveRoleFromDbInstanceRequest.class))).thenThrow(DbInstanceRoleNotFoundException.class);
        final AddRoleToDbInstanceResponse addRoleToDBInstanceResponse = AddRoleToDbInstanceResponse.builder().build();
        when(rdsProxy.client().addRoleToDBInstance(any(AddRoleToDbInstanceRequest.class))).thenReturn(addRoleToDBInstanceResponse);

        final CallbackContext context = new CallbackContext();
        context.setUpdated(true);
        context.setRebooted(true);

        test_handleRequest_base(
                context,
                transitions::remove,
                () -> RESOURCE_MODEL_BLDR().build(),
                () -> RESOURCE_MODEL_ALTER,
                expectSuccess()
        );

        verify(rdsProxy.client()).removeRoleFromDBInstance(any(RemoveRoleFromDbInstanceRequest.class));
        verify(rdsProxy.client(), times(2)).addRoleToDBInstance(any(AddRoleToDbInstanceRequest.class));
    }

    @Test
    public void handleRequest_CreateAlreadyExistingRole() {
        // compute a complete sequence of transitions from the initial set of roles to the final one.
        final Queue<DBInstance> transitions = new ConcurrentLinkedQueue<>(
                computeAssociatedRoleTransitions(DB_INSTANCE_ACTIVE, ASSOCIATED_ROLES, ASSOCIATED_ROLES_ALTER)
        );
        // We expect describeDBInstances to be called 2 more times: for tag mutation and for the final resource fetch.
        transitions.add(DB_INSTANCE_ACTIVE.toBuilder()
                .associatedRoles(Translator.translateAssociatedRolesToSdk(ASSOCIATED_ROLES_ALTER))
                .build());
        transitions.add(DB_INSTANCE_ACTIVE.toBuilder()
                .associatedRoles(Translator.translateAssociatedRolesToSdk(ASSOCIATED_ROLES_ALTER))
                .build());

        final RemoveRoleFromDbInstanceResponse removeRoleFromDBInstanceResponse = RemoveRoleFromDbInstanceResponse.builder().build();
        when(rdsProxy.client().removeRoleFromDBInstance(any(RemoveRoleFromDbInstanceRequest.class))).thenReturn(removeRoleFromDBInstanceResponse);

        when(rdsProxy.client().addRoleToDBInstance(any(AddRoleToDbInstanceRequest.class))).thenThrow(DbInstanceRoleAlreadyExistsException.class);

        final CallbackContext context = new CallbackContext();
        context.setUpdated(true);

        test_handleRequest_base(
                context,
                transitions::remove,
                () -> RESOURCE_MODEL_BLDR().build(),
                () -> RESOURCE_MODEL_ALTER,
                expectSuccess()
        );

        verify(rdsProxy.client()).removeRoleFromDBInstance(any(RemoveRoleFromDbInstanceRequest.class));
        verify(rdsProxy.client()).addRoleToDBInstance(any(AddRoleToDbInstanceRequest.class));
    }

    @Test
    public void handleRequest_UpdateRoles_InternalExceptionOnAdd() {
        when(rdsProxy.client().addRoleToDBInstance(any(AddRoleToDbInstanceRequest.class))).then(res -> {
            throw new RuntimeException(MSG_RUNTIME_ERR);
        });

        final RemoveRoleFromDbInstanceResponse removeRoleFromDBInstanceResponse = RemoveRoleFromDbInstanceResponse.builder().build();
        when(rdsProxy.client().removeRoleFromDBInstance(any(RemoveRoleFromDbInstanceRequest.class))).thenReturn(removeRoleFromDBInstanceResponse);

        final CallbackContext context = new CallbackContext();
        context.setUpdated(true);
        context.setRebooted(true);

        test_handleRequest_base(
                context,
                () -> DB_INSTANCE_ACTIVE,
                () -> RESOURCE_MODEL_BLDR().build(),
                () -> RESOURCE_MODEL_ALTER,
                expectFailed(HandlerErrorCode.InternalFailure)
        );

        verify(rdsProxy.client()).removeRoleFromDBInstance(any(RemoveRoleFromDbInstanceRequest.class));
        verify(rdsProxy.client()).addRoleToDBInstance(any(AddRoleToDbInstanceRequest.class));
    }

    @Test
    public void handleRequest_UpdateRolesInternalExceptionOnRemove() {
        when(rdsProxy.client().removeRoleFromDBInstance(any(RemoveRoleFromDbInstanceRequest.class))).then(res -> {
            throw new RuntimeException(MSG_RUNTIME_ERR);
        });

        final CallbackContext context = new CallbackContext();
        context.setUpdated(true);
        context.setRebooted(true);

        test_handleRequest_base(
                context,
                null,
                () -> RESOURCE_MODEL_BLDR().build(),
                () -> RESOURCE_MODEL_ALTER,
                expectFailed(HandlerErrorCode.InternalFailure)
        );

        verify(rdsProxy.client()).removeRoleFromDBInstance(any(RemoveRoleFromDbInstanceRequest.class));
    }

    @Test
    public void handleRequest_UpdateRolesInternalExceptionOnTagging() {
        final Queue<DBInstance> transitions = new ConcurrentLinkedQueue<>(
                computeAssociatedRoleTransitions(DB_INSTANCE_ACTIVE, ASSOCIATED_ROLES, ASSOCIATED_ROLES_ALTER)
        );

        final AddRoleToDbInstanceResponse addRoleToDBInstanceResponse = AddRoleToDbInstanceResponse.builder().build();
        when(rdsProxy.client().addRoleToDBInstance(any(AddRoleToDbInstanceRequest.class))).thenReturn(addRoleToDBInstanceResponse);
        final RemoveRoleFromDbInstanceResponse removeRoleFromDBInstanceResponse = RemoveRoleFromDbInstanceResponse.builder().build();
        when(rdsProxy.client().removeRoleFromDBInstance(any(RemoveRoleFromDbInstanceRequest.class))).thenReturn(removeRoleFromDBInstanceResponse);

        final CallbackContext context = new CallbackContext();
        context.setUpdated(true);
        context.setRebooted(true);
        context.setUpdatedRoles(false);

        test_handleRequest_base(
                context,
                () -> {
                    if (!transitions.isEmpty()) {
                        return transitions.remove();
                    }
                    throw new RuntimeException(MSG_RUNTIME_ERR);
                },
                () -> RESOURCE_MODEL_BLDR().build(),
                () -> RESOURCE_MODEL_ALTER,
                expectFailed(HandlerErrorCode.InternalFailure)
        );

        verify(rdsProxy.client(), times(2)).addRoleToDBInstance(any(AddRoleToDbInstanceRequest.class));
        verify(rdsProxy.client(), times(1)).removeRoleFromDBInstance(any(RemoveRoleFromDbInstanceRequest.class));
        verify(rdsProxy.client(), times(5)).describeDBInstances(any(DescribeDbInstancesRequest.class));
    }

    @Test
    public void handleRequest_UpdateRolesAndTags() {
        // compute a complete sequence of transitions from the initial set of roles to the final one.
        final Queue<DBInstance> transitions = new ConcurrentLinkedQueue<>(
                computeAssociatedRoleTransitions(DB_INSTANCE_ACTIVE, ASSOCIATED_ROLES, ASSOCIATED_ROLES_ALTER)
        );
        // We expect describeDBInstances to be called 3 more times: for tag mutation, reboot check and the final resource fetch.
        for (int i = 0; i < 3; i++) {
            transitions.add(DB_INSTANCE_ACTIVE.toBuilder()
                    .associatedRoles(Translator.translateAssociatedRolesToSdk(ASSOCIATED_ROLES_ALTER))
                    .build());
        }

        final RemoveTagsFromResourceResponse removeTagsFromResourceResponse = RemoveTagsFromResourceResponse.builder().build();
        when(rdsProxy.client().removeTagsFromResource(any(RemoveTagsFromResourceRequest.class))).thenReturn(removeTagsFromResourceResponse);
        final AddTagsToResourceResponse addTagsToResourceResponse = AddTagsToResourceResponse.builder().build();
        when(rdsProxy.client().addTagsToResource(any(AddTagsToResourceRequest.class))).thenReturn(addTagsToResourceResponse);
        final AddRoleToDbInstanceResponse addRoleToDBInstanceResponse = AddRoleToDbInstanceResponse.builder().build();
        when(rdsProxy.client().addRoleToDBInstance(any(AddRoleToDbInstanceRequest.class))).thenReturn(addRoleToDBInstanceResponse);
        final RemoveRoleFromDbInstanceResponse removeRoleFromDBInstanceResponse = RemoveRoleFromDbInstanceResponse.builder().build();
        when(rdsProxy.client().removeRoleFromDBInstance(any(RemoveRoleFromDbInstanceRequest.class))).thenReturn(removeRoleFromDBInstanceResponse);

        final CallbackContext context = new CallbackContext();
        context.setUpdated(true);
        context.setRebooted(true);

        test_handleRequest_base(
                context,
                ResourceHandlerRequest.<ResourceModel>builder()
                        .previousResourceTags(Translator.translateTagsToRequest(TAG_LIST))
                        .desiredResourceTags(Translator.translateTagsToRequest(TAG_LIST_ALTER)),
                transitions::remove,
                () -> RESOURCE_MODEL_BLDR().build(),
                () -> RESOURCE_MODEL_ALTER,
                expectSuccess()
        );

        verify(rdsProxy.client()).addTagsToResource(any(AddTagsToResourceRequest.class));
        verify(rdsProxy.client()).removeTagsFromResource(any(RemoveTagsFromResourceRequest.class));
        verify(rdsProxy.client(), times(2)).addRoleToDBInstance(any(AddRoleToDbInstanceRequest.class));
        verify(rdsProxy.client()).removeRoleFromDBInstance(any(RemoveRoleFromDbInstanceRequest.class));
        verify(rdsProxy.client(), times(6)).describeDBInstances(any(DescribeDbInstancesRequest.class));
    }

    @Test
    public void handleRequest_ShouldReboot_Success() {
        final DBInstance dbInstancePendingReboot = DB_INSTANCE_ACTIVE.toBuilder().dbParameterGroups(
                ImmutableList.of(DBParameterGroupStatus.builder()
                        .dbParameterGroupName(DB_PARAMETER_GROUP_NAME_DEFAULT)
                        .parameterApplyStatus(UpdateHandler.PENDING_REBOOT_STATUS)
                        .build())
        ).build();

        final RebootDbInstanceResponse rebootDbInstanceResponse = RebootDbInstanceResponse.builder().build();
        when(rdsProxy.client().rebootDBInstance(any(RebootDbInstanceRequest.class))).thenReturn(rebootDbInstanceResponse);

        final CallbackContext context = new CallbackContext();
        context.setUpdated(true);
        context.setRebooted(false);
        context.setUpdatedRoles(true);

        test_handleRequest_base(
                context,
                ResourceHandlerRequest.<ResourceModel>builder().rollback(true),
                () -> dbInstancePendingReboot,
                () -> RESOURCE_MODEL_BLDR().build(),
                () -> RESOURCE_MODEL_BLDR().build(),
                expectSuccess()
        );

        verify(rdsProxy.client()).rebootDBInstance(any(RebootDbInstanceRequest.class));
        verify(rdsProxy.client(), times(3)).describeDBInstances(any(DescribeDbInstancesRequest.class));
    }

    @Test
    public void handleRequest_SetParameterGroupName() {
        final DescribeDbParameterGroupsResponse describeDbParameterGroupsResponse = DescribeDbParameterGroupsResponse.builder()
                .dbParameterGroups(ImmutableList.of(DBParameterGroup.builder().build()))
                .build();
        when(rdsProxy.client().describeDBParameterGroups(any(DescribeDbParameterGroupsRequest.class))).thenReturn(describeDbParameterGroupsResponse);

        final DescribeDbEngineVersionsResponse describeDbEngineVersionsResponse = DescribeDbEngineVersionsResponse.builder()
                .dbEngineVersions(DBEngineVersion.builder().build())
                .build();
        when(rdsProxy.client().describeDBEngineVersions(any(DescribeDbEngineVersionsRequest.class))).thenReturn(describeDbEngineVersionsResponse);

        // Altering the db parameter group name attribute invokes setParameterGroupName
        final ResourceModel desiredModel = RESOURCE_MODEL_BLDR()
                .dBParameterGroupName(DB_PARAMETER_GROUP_NAME_ALTER)
                .engineVersion(ENGINE_VERSION_MYSQL_80)
                .build();
        final ResourceModel previousModel = RESOURCE_MODEL_BLDR()
                .dBParameterGroupName(DB_PARAMETER_GROUP_NAME_DEFAULT)
                .engineVersion(ENGINE_VERSION_MYSQL_56)
                .build();

        final CallbackContext context = new CallbackContext();
        context.setUpdated(true); // this is an emulation of a re-entrance

        test_handleRequest_base(
                context,
                ResourceHandlerRequest.<ResourceModel>builder().rollback(true),
                () -> DB_INSTANCE_ACTIVE,
                () -> previousModel,
                () -> desiredModel,
                expectSuccess()
        );

        verify(rdsProxy.client()).describeDBParameterGroups(any(DescribeDbParameterGroupsRequest.class));
        verify(rdsProxy.client()).describeDBEngineVersions(any(DescribeDbEngineVersionsRequest.class));
        verify(rdsProxy.client(), times(2)).describeDBInstances(any(DescribeDbInstancesRequest.class));
    }

    @Test
    public void handleRequest_SetParameterGroupName_EmptyDbParameterGroupName() {
        final ResourceModel desiredModel = RESOURCE_MODEL_BLDR()
                .dBParameterGroupName(null)
                .engineVersion(ENGINE_VERSION_MYSQL_80)
                .build();
        // An empty db parameter group name will cause setParameterGroupName to return earlier
        final ResourceModel previousModel = RESOURCE_MODEL_BLDR()
                .dBParameterGroupName(DB_PARAMETER_GROUP_NAME_DEFAULT)
                .engineVersion(ENGINE_VERSION_MYSQL_56)
                .build();

        final CallbackContext context = new CallbackContext();
        context.setUpdated(true); // this is an emulation of a re-entrance

        test_handleRequest_base(
                context,
                ResourceHandlerRequest.<ResourceModel>builder().rollback(true),
                () -> DB_INSTANCE_ACTIVE,
                () -> previousModel,
                () -> desiredModel,
                expectSuccess()
        );

        verify(rdsProxy.client(), times(2)).describeDBInstances(any(DescribeDbInstancesRequest.class));
    }

    @Test
    public void handleRequest_SetParameterGroupName_NoDbParameterGroups() {
        final DescribeDbParameterGroupsResponse describeDbParameterGroupsResponse = DescribeDbParameterGroupsResponse.builder()
                .dbParameterGroups(ImmutableList.of()) // empty db parameter group set
                .build();
        when(rdsProxy.client().describeDBParameterGroups(any(DescribeDbParameterGroupsRequest.class))).thenReturn(describeDbParameterGroupsResponse);

        // Altering the db parameter group name attribute invokes setParameterGroupName
        final ResourceModel desiredModel = RESOURCE_MODEL_BLDR()
                .dBParameterGroupName(DB_PARAMETER_GROUP_NAME_ALTER)
                .engineVersion(ENGINE_VERSION_MYSQL_80)
                .build();
        final ResourceModel previousModel = RESOURCE_MODEL_BLDR()
                .dBParameterGroupName(DB_PARAMETER_GROUP_NAME_DEFAULT)
                .engineVersion(ENGINE_VERSION_MYSQL_56)
                .build();

        final CallbackContext context = new CallbackContext();
        context.setUpdated(true); // this is an emulation of a re-entrance

        test_handleRequest_base(
                context,
                ResourceHandlerRequest.<ResourceModel>builder().rollback(true),
                () -> DB_INSTANCE_ACTIVE,
                () -> previousModel,
                () -> desiredModel,
                expectSuccess()
        );

        verify(rdsProxy.client()).describeDBParameterGroups(any(DescribeDbParameterGroupsRequest.class));
        verify(rdsProxy.client(), times(2)).describeDBInstances(any(DescribeDbInstancesRequest.class));
    }

    @Test
    public void handleRequest_SetParameterGroupName_EmptyDbEngineVersions() {
        final DescribeDbParameterGroupsResponse describeDbParameterGroupsResponse = DescribeDbParameterGroupsResponse.builder()
                .dbParameterGroups(ImmutableList.of(DBParameterGroup.builder().build()))
                .build();
        when(rdsProxy.client().describeDBParameterGroups(any(DescribeDbParameterGroupsRequest.class))).thenReturn(describeDbParameterGroupsResponse);

        final DescribeDbEngineVersionsResponse describeDbEngineVersionsResponse = DescribeDbEngineVersionsResponse.builder()
                .dbEngineVersions(ImmutableList.of()) // empty list
                .build();
        when(rdsProxy.client().describeDBEngineVersions(any(DescribeDbEngineVersionsRequest.class))).thenReturn(describeDbEngineVersionsResponse);

        final CallbackContext context = new CallbackContext();
        context.setUpdated(true); // this is an emulation of a re-entrance

        // Altering the db parameter group name attribute invokes setParameterGroupName
        final ResourceModel desiredModel = RESOURCE_MODEL_BLDR()
                .dBParameterGroupName(DB_PARAMETER_GROUP_NAME_ALTER)
                .engineVersion(ENGINE_VERSION_MYSQL_80)
                .build();
        final ResourceModel previousModel = RESOURCE_MODEL_BLDR()
                .dBParameterGroupName(DB_PARAMETER_GROUP_NAME_DEFAULT)
                .engineVersion(ENGINE_VERSION_MYSQL_56)
                .build();

        test_handleRequest_base(
                context,
                ResourceHandlerRequest.<ResourceModel>builder().rollback(true),
                () -> DB_INSTANCE_ACTIVE,
                () -> previousModel,
                () -> desiredModel,
                expectSuccess()
        );

        verify(rdsProxy.client()).describeDBParameterGroups(any(DescribeDbParameterGroupsRequest.class));
        verify(rdsProxy.client()).describeDBEngineVersions(any(DescribeDbEngineVersionsRequest.class));
        verify(rdsProxy.client(), times(2)).describeDBInstances(any(DescribeDbInstancesRequest.class));
    }

    @Test
    public void handleRequest_SetDefaultVpcId() {
        final DescribeSecurityGroupsResponse describeSecurityGroupsResponse = DescribeSecurityGroupsResponse.builder()
                .securityGroups(SecurityGroup.builder().groupName(DB_SECURITY_GROUP_DEFAULT).groupId(DB_SECURITY_GROUP_ID).build())
                .build();
        when(ec2Proxy.client().describeSecurityGroups(any(DescribeSecurityGroupsRequest.class))).thenReturn(describeSecurityGroupsResponse);

        final CallbackContext context = new CallbackContext();
        context.setUpdated(true); // this is an emulation of a re-entrance

        test_handleRequest_base(
                context,
                () -> DB_INSTANCE_ACTIVE.toBuilder().dbSubnetGroup(
                        DBSubnetGroup.builder().vpcId(DB_SECURITY_GROUP_VPC_ID).build()
                ).build(),
                () -> RESOURCE_MODEL_BLDR().build(),
                () -> RESOURCE_MODEL_BLDR()
                        .vPCSecurityGroups(Collections.emptyList())
                        .build(),
                expectSuccess()
        );

        verify(ec2Proxy.client()).describeSecurityGroups(any(DescribeSecurityGroupsRequest.class));
        verify(rdsProxy.client(), times(3)).describeDBInstances(any(DescribeDbInstancesRequest.class));
    }
}
