package software.amazon.rds.dbinstance;

import static org.mockito.Mockito.any;
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

import lombok.Getter;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.AddRoleToDbInstanceRequest;
import software.amazon.awssdk.services.rds.model.AddRoleToDbInstanceResponse;
import software.amazon.awssdk.services.rds.model.CreateDbInstanceReadReplicaRequest;
import software.amazon.awssdk.services.rds.model.CreateDbInstanceReadReplicaResponse;
import software.amazon.awssdk.services.rds.model.CreateDbInstanceRequest;
import software.amazon.awssdk.services.rds.model.CreateDbInstanceResponse;
import software.amazon.awssdk.services.rds.model.DBInstance;
import software.amazon.awssdk.services.rds.model.DbInstanceAlreadyExistsException;
import software.amazon.awssdk.services.rds.model.DescribeDbInstancesRequest;
import software.amazon.awssdk.services.rds.model.ModifyDbInstanceRequest;
import software.amazon.awssdk.services.rds.model.ModifyDbInstanceResponse;
import software.amazon.awssdk.services.rds.model.RebootDbInstanceRequest;
import software.amazon.awssdk.services.rds.model.RebootDbInstanceResponse;
import software.amazon.awssdk.services.rds.model.RestoreDbInstanceFromDbSnapshotRequest;
import software.amazon.awssdk.services.rds.model.RestoreDbInstanceFromDbSnapshotResponse;
import software.amazon.cloudformation.proxy.AmazonWebServicesClientProxy;
import software.amazon.cloudformation.proxy.HandlerErrorCode;
import software.amazon.cloudformation.proxy.ProxyClient;
import software.amazon.cloudformation.proxy.delay.Constant;

@ExtendWith(MockitoExtension.class)
public class CreateHandlerTest extends AbstractHandlerTest {

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
    private CreateHandler handler;

    @BeforeEach
    public void setup() {
        handler = new CreateHandler(
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
    public void handleRequest_RestoreDBInstanceFromSnapshot_Success() {
        final RestoreDbInstanceFromDbSnapshotResponse restoreResponse = RestoreDbInstanceFromDbSnapshotResponse.builder().build();
        when(rdsProxy.client().restoreDBInstanceFromDBSnapshot(any(RestoreDbInstanceFromDbSnapshotRequest.class)))
                .thenReturn(restoreResponse);

        final CallbackContext context = new CallbackContext();
        context.setCreated(false);
        context.setUpdated(true);
        context.setRebooted(true);
        context.setUpdatedRoles(true);

        test_handleRequest_base(
                context,
                () -> DB_INSTANCE_ACTIVE,
                () -> RESOURCE_MODEL_RESTORING_FROM_SNAPSHOT,
                expectSuccess()
        );

        verify(rdsProxy.client(), times(1)).restoreDBInstanceFromDBSnapshot(any(RestoreDbInstanceFromDbSnapshotRequest.class));
        verify(rdsProxy.client(), times(2)).describeDBInstances(any(DescribeDbInstancesRequest.class));
    }

    @Test
    public void handleRequest_RestoreDBInstanceFromSnapshot_AlreadyExists() {
        when(rdsProxy.client().restoreDBInstanceFromDBSnapshot(any(RestoreDbInstanceFromDbSnapshotRequest.class)))
                .thenThrow(DbInstanceAlreadyExistsException.builder().message(MSG_ALREADY_EXISTS_ERR).build());

        final CallbackContext context = new CallbackContext();
        context.setCreated(false);

        test_handleRequest_base(
                context,
                null,
                () -> RESOURCE_MODEL_RESTORING_FROM_SNAPSHOT,
                expectFailed(HandlerErrorCode.AlreadyExists)
        );

        verify(rdsProxy.client(), times(1)).restoreDBInstanceFromDBSnapshot(any(RestoreDbInstanceFromDbSnapshotRequest.class));
    }

    @Test
    public void handleRequest_RestoreDBInstanceFromSnapshot_RuntimeException() {
        when(rdsProxy.client().restoreDBInstanceFromDBSnapshot(any(RestoreDbInstanceFromDbSnapshotRequest.class)))
                .thenThrow(new RuntimeException(MSG_RUNTIME_ERR));

        final CallbackContext context = new CallbackContext();
        context.setCreated(false);

        test_handleRequest_base(
                context,
                null,
                () -> RESOURCE_MODEL_RESTORING_FROM_SNAPSHOT,
                expectFailed(HandlerErrorCode.InternalFailure)
        );

        verify(rdsProxy.client(), times(1)).restoreDBInstanceFromDBSnapshot(any(RestoreDbInstanceFromDbSnapshotRequest.class));
    }

    @Test
    public void handleRequest_CreateReadReplica_Create_InProgress() {
        final CreateDbInstanceReadReplicaResponse createResponse = CreateDbInstanceReadReplicaResponse.builder().build();
        when(rdsProxy.client().createDBInstanceReadReplica(any(CreateDbInstanceReadReplicaRequest.class))).thenReturn(createResponse);

        final CallbackContext context = new CallbackContext();
        context.setCreated(false);
        context.setUpdated(true);
        context.setRebooted(true);
        context.setUpdatedRoles(true);

        test_handleRequest_base(
                context,
                () -> DB_INSTANCE_ACTIVE,
                () -> RESOURCE_MODEL_READ_REPLICA,
                expectSuccess()
        );

        verify(rdsProxy.client(), times(1)).createDBInstanceReadReplica(any(CreateDbInstanceReadReplicaRequest.class));
        verify(rdsProxy.client(), times(2)).describeDBInstances(any(DescribeDbInstancesRequest.class));
    }

    @Test
    public void handleRequest_CreateReadReplica_Update_InProgress() {
        final ModifyDbInstanceResponse modifyDbInstanceResponse = ModifyDbInstanceResponse.builder().build();
        when(rdsProxy.client().modifyDBInstance(any(ModifyDbInstanceRequest.class))).thenReturn(modifyDbInstanceResponse);

        final CallbackContext context = new CallbackContext();
        context.setCreated(true);
        context.setUpdated(false);
        context.setRebooted(true);
        context.setUpdatedRoles(true);

        test_handleRequest_base(
                context,
                () -> DB_INSTANCE_ACTIVE,
                () -> RESOURCE_MODEL_READ_REPLICA,
                expectSuccess()
        );

        verify(rdsProxy.client(), times(1)).modifyDBInstance(any(ModifyDbInstanceRequest.class));
        verify(rdsProxy.client(), times(2)).describeDBInstances(any(DescribeDbInstancesRequest.class));
    }

    @Test
    public void handleRequest_CreateReadReplica_Reboot_Success() {
        final RebootDbInstanceResponse rebootDbInstanceResponse = RebootDbInstanceResponse.builder().build();
        when(rdsProxy.client().rebootDBInstance(any(RebootDbInstanceRequest.class))).thenReturn(rebootDbInstanceResponse);

        final CallbackContext context = new CallbackContext();
        context.setCreated(true);
        context.setUpdated(true);
        context.setRebooted(false);
        context.setUpdatedRoles(true);

        test_handleRequest_base(
                context,
                () -> DB_INSTANCE_ACTIVE,
                () -> RESOURCE_MODEL_READ_REPLICA,
                expectSuccess()
        );

        verify(rdsProxy.client(), times(1)).rebootDBInstance(any(RebootDbInstanceRequest.class));
        verify(rdsProxy.client(), times(2)).describeDBInstances(any(DescribeDbInstancesRequest.class));
    }

    @Test
    public void handleRequest_CreateReadReplica_Success() {
        final CreateDbInstanceReadReplicaResponse createDbInstanceReadReplicaResponse = CreateDbInstanceReadReplicaResponse.builder().build();
        when(rdsProxy.client().createDBInstanceReadReplica(any(CreateDbInstanceReadReplicaRequest.class))).thenReturn(createDbInstanceReadReplicaResponse);

        final CallbackContext context = new CallbackContext();
        context.setCreated(false);
        context.setUpdated(true);
        context.setRebooted(true);
        context.setUpdatedRoles(true);

        test_handleRequest_base(
                context,
                () -> DB_INSTANCE_ACTIVE,
                () -> RESOURCE_MODEL_READ_REPLICA,
                expectSuccess()
        );

        verify(rdsProxy.client(), times(1)).createDBInstanceReadReplica(any(CreateDbInstanceReadReplicaRequest.class));
        verify(rdsProxy.client(), times(2)).describeDBInstances(any(DescribeDbInstancesRequest.class));
    }

    @Test
    public void handleRequest_CreateReadReplica_AlreadyExists() {
        when(rdsProxy.client().createDBInstanceReadReplica(any(CreateDbInstanceReadReplicaRequest.class)))
                .thenThrow(DbInstanceAlreadyExistsException.builder().message(MSG_ALREADY_EXISTS_ERR).build());

        final CallbackContext context = new CallbackContext();
        context.setCreated(false);

        test_handleRequest_base(
                context,
                null,
                () -> RESOURCE_MODEL_READ_REPLICA,
                expectFailed(HandlerErrorCode.AlreadyExists)
        );

        verify(rdsProxy.client(), times(1)).createDBInstanceReadReplica(any(CreateDbInstanceReadReplicaRequest.class));
    }

    @Test
    public void handleRequest_CreateReadReplica_RuntimeException() {
        when(rdsProxy.client().createDBInstanceReadReplica(any(CreateDbInstanceReadReplicaRequest.class)))
                .thenThrow(new RuntimeException(MSG_RUNTIME_ERR));

        final CallbackContext context = new CallbackContext();
        context.setCreated(false);

        test_handleRequest_base(
                context,
                null,
                () -> RESOURCE_MODEL_READ_REPLICA,
                expectFailed(HandlerErrorCode.InternalFailure)
        );

        verify(rdsProxy.client(), times(1)).createDBInstanceReadReplica(any(CreateDbInstanceReadReplicaRequest.class));
    }

    @Test
    public void handleRequest_CreateNewInstance_AlreadyExists() {
        when(rdsProxy.client().createDBInstance(any(CreateDbInstanceRequest.class)))
                .thenThrow(DbInstanceAlreadyExistsException.builder().message(MSG_ALREADY_EXISTS_ERR).build());

        final CallbackContext context = new CallbackContext();
        context.setCreated(false);

        test_handleRequest_base(
                context,
                null,
                () -> RESOURCE_MODEL_BLDR().build(),
                expectFailed(HandlerErrorCode.AlreadyExists)
        );

        verify(rdsProxy.client(), times(1)).createDBInstance(any(CreateDbInstanceRequest.class));
    }

    @Test
    public void handleRequest_CreateNewInstance_RuntimeException() {
        when(rdsProxy.client().createDBInstance(any(CreateDbInstanceRequest.class)))
                .thenThrow(new RuntimeException(MSG_RUNTIME_ERR));

        final CallbackContext context = new CallbackContext();
        context.setCreated(false);

        test_handleRequest_base(
                context,
                null,
                () -> RESOURCE_MODEL_BLDR().build(),
                expectFailed(HandlerErrorCode.InternalFailure)
        );

        verify(rdsProxy.client(), times(1)).createDBInstance(any(CreateDbInstanceRequest.class));
    }

    @Test
    public void handleRequest_CreateNewInstance_Success() {
        final CallbackContext context = new CallbackContext();
        context.setCreated(true);
        context.setUpdated(true);
        context.setRebooted(true);
        context.setUpdatedRoles(true);

        test_handleRequest_base(
                context,
                () -> DB_INSTANCE_ACTIVE,
                () -> RESOURCE_MODEL_BLDR().build(),
                expectSuccess()
        );

        verify(rdsProxy.client(), times(1)).describeDBInstances(any(DescribeDbInstancesRequest.class));
    }

    @Test
    public void handleRequest_CreateNewInstance_UpdateRoles_Success() {
        final AddRoleToDbInstanceResponse addRoleToDbInstanceResponse = AddRoleToDbInstanceResponse.builder().build();
        when(rdsProxy.client().addRoleToDBInstance(any(AddRoleToDbInstanceRequest.class))).thenReturn(addRoleToDbInstanceResponse);

        final Queue<DBInstance> transitions = new ConcurrentLinkedQueue<>(
                computeAssociatedRoleTransitions(DB_INSTANCE_ACTIVE, Collections.emptyList(), ASSOCIATED_ROLES)
        );
        transitions.add(DB_INSTANCE_ACTIVE.toBuilder()
                .associatedRoles(Translator.translateAssociatedRolesToSdk(ASSOCIATED_ROLES))
                .build());

        final CallbackContext context = new CallbackContext();
        context.setCreated(true);
        context.setUpdated(true);
        context.setRebooted(true);
        context.setUpdatedRoles(false);

        test_handleRequest_base(
                context,
                transitions::remove,
                () -> RESOURCE_MODEL_BLDR().build(),
                expectSuccess()
        );

        verify(rdsProxy.client(), times(3)).describeDBInstances(any(DescribeDbInstancesRequest.class));
        verify(rdsProxy.client(), times(1)).addRoleToDBInstance(any(AddRoleToDbInstanceRequest.class));
    }

    @Test
    public void handleRequest_CreateNewInstance_NoIdentifier_InProgress() {
        final CreateDbInstanceResponse createResponse = CreateDbInstanceResponse.builder().build();
        when(rdsProxy.client().createDBInstance(any(CreateDbInstanceRequest.class))).thenReturn(createResponse);

        final CallbackContext context = new CallbackContext();
        context.setCreated(false);

        test_handleRequest_base(
                context,
                () -> DB_INSTANCE_ACTIVE,
                () -> RESOURCE_MODEL_NO_IDENTIFIER,
                expectSuccess()
        );

        verify(rdsProxy.client(), times(1)).createDBInstance(any(CreateDbInstanceRequest.class));
        verify(rdsProxy.client(), times(2)).describeDBInstances(any(DescribeDbInstancesRequest.class));
    }

    @Test
    public void handleRequest_CreateNewInstance_ShouldUpdateAfterCreate_CACertificateIdentifier_Success() {
        final ModifyDbInstanceResponse modifyDbInstanceResponse = ModifyDbInstanceResponse.builder().build();
        when(rdsProxy.client().modifyDBInstance(any(ModifyDbInstanceRequest.class))).thenReturn(modifyDbInstanceResponse);

        final DBInstance dbInstance = DB_INSTANCE_BASE.toBuilder()
                .caCertificateIdentifier(CA_CERTIFICATE_IDENTIFIER_NON_EMPTY)
                .build();
        final CallbackContext context = new CallbackContext();
        context.setCreated(true);
        context.setUpdated(false);

        test_handleRequest_base(
                context,
                () -> dbInstance,
                () -> Translator.translateDbInstanceFromSdk(dbInstance),
                expectSuccess()
        );

        verify(rdsProxy.client(), times(1)).modifyDBInstance(any(ModifyDbInstanceRequest.class));
        verify(rdsProxy.client(), times(2)).describeDBInstances(any(DescribeDbInstancesRequest.class));
    }

    @Test
    public void handleRequest_CreateNewInstance_ShouldNotReboot_Success() {
        final CallbackContext context = new CallbackContext();
        context.setCreated(true);
        context.setUpdated(true);
        context.setRebooted(false);
        context.setUpdatedRoles(true);

        test_handleRequest_base(
                context,
                () -> DB_INSTANCE_ACTIVE,
                () -> RESOURCE_MODEL_BLDR().dBParameterGroupName(null).build(),
                expectSuccess()
        );

        verify(rdsProxy.client(), times(1)).describeDBInstances(any(DescribeDbInstancesRequest.class));
    }

    @Test
    public void handleRequest_CreateNewInstance_ShouldNotUpdate_Success() {
        final CallbackContext context = new CallbackContext();
        context.setCreated(true);
        context.setUpdated(false);
        context.setRebooted(false);
        context.setUpdatedRoles(true);

        test_handleRequest_base(
                context,
                () -> DB_INSTANCE_ACTIVE,
                () -> RESOURCE_MODEL_BLDR().cACertificateIdentifier(null).build(),
                expectSuccess()
        );

        verify(rdsProxy.client(), times(1)).describeDBInstances(any(DescribeDbInstancesRequest.class));
    }

    @Test
    public void handleRequest_CreateReadReplica_DbSecurityGroups_ShouldUpdate_Success() {
        final ModifyDbInstanceResponse modifyDbInstanceResponse = ModifyDbInstanceResponse.builder().build();
        when(rdsProxy.client().modifyDBInstance(any(ModifyDbInstanceRequest.class))).thenReturn(modifyDbInstanceResponse);

        final CallbackContext context = new CallbackContext();
        context.setCreated(true);
        context.setUpdated(false);
        context.setRebooted(false);
        context.setUpdatedRoles(true);

        test_handleRequest_base(
                context,
                () -> DB_INSTANCE_ACTIVE,
                () -> RESOURCE_MODEL_BAREBONE_BLDR()
                        .sourceDBInstanceIdentifier(SOURCE_DB_INSTANCE_IDENTIFIER_NON_EMPTY) // Read replica
                        .dBSecurityGroups(Collections.singletonList(DB_SECURITY_GROUP_DEFAULT))
                        .build(),
                expectSuccess()
        );

        verify(rdsProxy.client(), times(1)).modifyDBInstance(any(ModifyDbInstanceRequest.class));
        verify(rdsProxy.client(), times(2)).describeDBInstances(any(DescribeDbInstancesRequest.class));
    }

    @Test
    public void handleRequest_CreateReadReplica_AllocatedStorage_ShouldUpdate_Success() {
        final ModifyDbInstanceResponse modifyDbInstanceResponse = ModifyDbInstanceResponse.builder().build();
        when(rdsProxy.client().modifyDBInstance(any(ModifyDbInstanceRequest.class))).thenReturn(modifyDbInstanceResponse);

        final CallbackContext context = new CallbackContext();
        context.setCreated(true);
        context.setUpdated(false);
        context.setRebooted(false);
        context.setUpdatedRoles(true);

        test_handleRequest_base(
                context,
                () -> DB_INSTANCE_ACTIVE,
                () -> RESOURCE_MODEL_BAREBONE_BLDR()
                        .sourceDBInstanceIdentifier(SOURCE_DB_INSTANCE_IDENTIFIER_NON_EMPTY) // Read replica
                        .allocatedStorage(ALLOCATED_STORAGE.toString())
                        .build(),
                expectSuccess()
        );

        verify(rdsProxy.client(), times(1)).modifyDBInstance(any(ModifyDbInstanceRequest.class));
        verify(rdsProxy.client(), times(2)).describeDBInstances(any(DescribeDbInstancesRequest.class));
    }

    @Test
    public void handleRequest_CreateReadReplica_CACertificateIdentifier_ShouldUpdate_Success() {
        final ModifyDbInstanceResponse modifyDbInstanceResponse = ModifyDbInstanceResponse.builder().build();
        when(rdsProxy.client().modifyDBInstance(any(ModifyDbInstanceRequest.class))).thenReturn(modifyDbInstanceResponse);

        final CallbackContext context = new CallbackContext();
        context.setCreated(true);
        context.setUpdated(false);
        context.setRebooted(false);
        context.setUpdatedRoles(true);

        test_handleRequest_base(
                context,
                () -> DB_INSTANCE_ACTIVE,
                () -> RESOURCE_MODEL_BAREBONE_BLDR()
                        .sourceDBInstanceIdentifier(SOURCE_DB_INSTANCE_IDENTIFIER_NON_EMPTY) // Read replica
                        .cACertificateIdentifier(CA_CERTIFICATE_IDENTIFIER_NON_EMPTY)
                        .build(),
                expectSuccess()
        );

        verify(rdsProxy.client(), times(1)).modifyDBInstance(any(ModifyDbInstanceRequest.class));
        verify(rdsProxy.client(), times(2)).describeDBInstances(any(DescribeDbInstancesRequest.class));
    }

    @Test
    public void handleRequest_CreateReadReplica_DBParameterGroup_ShouldUpdate_Success() {
        final ModifyDbInstanceResponse modifyDbInstanceResponse = ModifyDbInstanceResponse.builder().build();
        when(rdsProxy.client().modifyDBInstance(any(ModifyDbInstanceRequest.class))).thenReturn(modifyDbInstanceResponse);

        final CallbackContext context = new CallbackContext();
        context.setCreated(true);
        context.setUpdated(false);
        context.setRebooted(true);
        context.setUpdatedRoles(true);

        test_handleRequest_base(
                context,
                () -> DB_INSTANCE_ACTIVE,
                () -> RESOURCE_MODEL_BAREBONE_BLDR()
                        .sourceDBInstanceIdentifier(SOURCE_DB_INSTANCE_IDENTIFIER_NON_EMPTY) // Read replica
                        .dBParameterGroupName(DB_PARAMETER_GROUP_NAME_DEFAULT)
                        .build(),
                expectSuccess()
        );

        verify(rdsProxy.client(), times(1)).modifyDBInstance(any(ModifyDbInstanceRequest.class));
        verify(rdsProxy.client(), times(2)).describeDBInstances(any(DescribeDbInstancesRequest.class));
    }

    @Test
    public void handleRequest_CreateReadReplica_EngineVersion_ShouldUpdate_Success() {
        final ModifyDbInstanceResponse modifyDbInstanceResponse = ModifyDbInstanceResponse.builder().build();
        when(rdsProxy.client().modifyDBInstance(any(ModifyDbInstanceRequest.class))).thenReturn(modifyDbInstanceResponse);

        final CallbackContext context = new CallbackContext();
        context.setCreated(true);
        context.setUpdated(false);
        context.setRebooted(false);
        context.setUpdatedRoles(true);

        test_handleRequest_base(
                context,
                () -> DB_INSTANCE_ACTIVE,
                () -> RESOURCE_MODEL_BAREBONE_BLDR()
                        .sourceDBInstanceIdentifier(SOURCE_DB_INSTANCE_IDENTIFIER_NON_EMPTY) // Read replica
                        .engineVersion(ENGINE_VERSION_MYSQL_56)
                        .build(),
                expectSuccess()
        );

        verify(rdsProxy.client(), times(1)).modifyDBInstance(any(ModifyDbInstanceRequest.class));
        verify(rdsProxy.client(), times(2)).describeDBInstances(any(DescribeDbInstancesRequest.class));
    }

    @Test
    public void handleRequest_CreateReadReplica_MasterUserPassword_ShouldUpdate_Success() {
        final ModifyDbInstanceResponse modifyDbInstanceResponse = ModifyDbInstanceResponse.builder().build();
        when(rdsProxy.client().modifyDBInstance(any(ModifyDbInstanceRequest.class))).thenReturn(modifyDbInstanceResponse);

        final CallbackContext context = new CallbackContext();
        context.setCreated(true);
        context.setUpdated(false);
        context.setRebooted(false);
        context.setUpdatedRoles(true);

        test_handleRequest_base(
                context,
                () -> DB_INSTANCE_ACTIVE,
                () -> RESOURCE_MODEL_BAREBONE_BLDR()
                        .sourceDBInstanceIdentifier(SOURCE_DB_INSTANCE_IDENTIFIER_NON_EMPTY) // Read replica
                        .masterUserPassword(MASTER_USER_PASSWORD)
                        .build(),
                expectSuccess()
        );

        verify(rdsProxy.client(), times(1)).modifyDBInstance(any(ModifyDbInstanceRequest.class));
        verify(rdsProxy.client(), times(2)).describeDBInstances(any(DescribeDbInstancesRequest.class));
    }

    @Test
    public void handleRequest_CreateReadReplica_PreferredBackupWindow_ShouldUpdate_Success() {
        final ModifyDbInstanceResponse modifyDbInstanceResponse = ModifyDbInstanceResponse.builder().build();
        when(rdsProxy.client().modifyDBInstance(any(ModifyDbInstanceRequest.class))).thenReturn(modifyDbInstanceResponse);

        final CallbackContext context = new CallbackContext();
        context.setCreated(true);
        context.setUpdated(false);
        context.setRebooted(false);
        context.setUpdatedRoles(true);

        test_handleRequest_base(
                context,
                () -> DB_INSTANCE_ACTIVE,
                () -> RESOURCE_MODEL_BAREBONE_BLDR()
                        .sourceDBInstanceIdentifier(SOURCE_DB_INSTANCE_IDENTIFIER_NON_EMPTY) // Read replica
                        .preferredBackupWindow(PREFERRED_BACKUP_WINDOW_NON_EMPTY)
                        .build(),
                expectSuccess()
        );

        verify(rdsProxy.client(), times(1)).modifyDBInstance(any(ModifyDbInstanceRequest.class));
        verify(rdsProxy.client(), times(2)).describeDBInstances(any(DescribeDbInstancesRequest.class));
    }

    @Test
    public void handleRequest_CreateReadReplica_PreferredMaintenanceWindow_ShouldUpdate_Success() {
        final ModifyDbInstanceResponse modifyDbInstanceResponse = ModifyDbInstanceResponse.builder().build();
        when(rdsProxy.client().modifyDBInstance(any(ModifyDbInstanceRequest.class))).thenReturn(modifyDbInstanceResponse);

        final CallbackContext context = new CallbackContext();
        context.setCreated(true);
        context.setUpdated(false);
        context.setRebooted(false);
        context.setUpdatedRoles(true);

        test_handleRequest_base(
                context,
                () -> DB_INSTANCE_ACTIVE,
                () -> RESOURCE_MODEL_BAREBONE_BLDR()
                        .sourceDBInstanceIdentifier(SOURCE_DB_INSTANCE_IDENTIFIER_NON_EMPTY) // Read replica
                        .preferredMaintenanceWindow(PREFERRED_BACKUP_WINDOW_NON_EMPTY)
                        .build(),
                expectSuccess()
        );

        verify(rdsProxy.client(), times(1)).modifyDBInstance(any(ModifyDbInstanceRequest.class));
        verify(rdsProxy.client(), times(2)).describeDBInstances(any(DescribeDbInstancesRequest.class));
    }

    @Test
    public void handleRequest_CreateReadReplica_BackupRetentionPeriod_ShouldUpdate_Success() {
        final ModifyDbInstanceResponse modifyDbInstanceResponse = ModifyDbInstanceResponse.builder().build();
        when(rdsProxy.client().modifyDBInstance(any(ModifyDbInstanceRequest.class))).thenReturn(modifyDbInstanceResponse);

        final CallbackContext context = new CallbackContext();
        context.setCreated(true);
        context.setUpdated(false);
        context.setRebooted(false);
        context.setUpdatedRoles(true);

        test_handleRequest_base(
                context,
                () -> DB_INSTANCE_ACTIVE,
                () -> RESOURCE_MODEL_BAREBONE_BLDR()
                        .sourceDBInstanceIdentifier(SOURCE_DB_INSTANCE_IDENTIFIER_NON_EMPTY) // Read replica
                        .backupRetentionPeriod(BACKUP_RETENTION_PERIOD_DEFAULT)
                        .build(),
                expectSuccess()
        );

        verify(rdsProxy.client(), times(1)).modifyDBInstance(any(ModifyDbInstanceRequest.class));
        verify(rdsProxy.client(), times(2)).describeDBInstances(any(DescribeDbInstancesRequest.class));
    }

    @Test
    public void handleRequest_CreateReadReplica_Iops_ShouldUpdate_Success() {
        final ModifyDbInstanceResponse modifyDbInstanceResponse = ModifyDbInstanceResponse.builder().build();
        when(rdsProxy.client().modifyDBInstance(any(ModifyDbInstanceRequest.class))).thenReturn(modifyDbInstanceResponse);

        final CallbackContext context = new CallbackContext();
        context.setCreated(true);
        context.setUpdated(false);
        context.setRebooted(false);
        context.setUpdatedRoles(true);

        test_handleRequest_base(
                context,
                () -> DB_INSTANCE_ACTIVE,
                () -> RESOURCE_MODEL_BAREBONE_BLDR()
                        .sourceDBInstanceIdentifier(SOURCE_DB_INSTANCE_IDENTIFIER_NON_EMPTY) // Read replica
                        .iops(IOPS_DEFAULT)
                        .build(),
                expectSuccess()
        );

        verify(rdsProxy.client(), times(1)).modifyDBInstance(any(ModifyDbInstanceRequest.class));
        verify(rdsProxy.client(), times(2)).describeDBInstances(any(DescribeDbInstancesRequest.class));
    }

    @Test
    public void handleRequest_CreateReadReplica_MaxAllocatedStorage_ShouldUpdate_Success() {
        final ModifyDbInstanceResponse modifyDbInstanceResponse = ModifyDbInstanceResponse.builder().build();
        when(rdsProxy.client().modifyDBInstance(any(ModifyDbInstanceRequest.class))).thenReturn(modifyDbInstanceResponse);

        final CallbackContext context = new CallbackContext();
        context.setCreated(true);
        context.setUpdated(false);
        context.setRebooted(false);
        context.setUpdatedRoles(true);

        test_handleRequest_base(
                context,
                () -> DB_INSTANCE_ACTIVE,
                () -> RESOURCE_MODEL_BAREBONE_BLDR()
                        .sourceDBInstanceIdentifier(SOURCE_DB_INSTANCE_IDENTIFIER_NON_EMPTY) // Read replica
                        .maxAllocatedStorage(MAX_ALLOCATED_STORAGE_DEFAULT)
                        .build(),
                expectSuccess()
        );

        verify(rdsProxy.client(), times(1)).modifyDBInstance(any(ModifyDbInstanceRequest.class));
        verify(rdsProxy.client(), times(2)).describeDBInstances(any(DescribeDbInstancesRequest.class));
    }
}
