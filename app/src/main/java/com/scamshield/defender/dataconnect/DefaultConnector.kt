
@file:Suppress(
  "KotlinRedundantDiagnosticSuppress",
  "LocalVariableName",
  "MayBeConstant",
  "RedundantVisibilityModifier",
  "RedundantCompanionReference",
  "RemoveEmptyClassBody",
  "SpellCheckingInspection",
  "LocalVariableName",
  "unused",
)

package com.scamshield.defender.dataconnect

import com.google.firebase.dataconnect.getInstance as _fdcGetInstance
import kotlin.time.Duration.Companion.milliseconds as _milliseconds

public interface DefaultConnector : com.google.firebase.dataconnect.generated.GeneratedConnector<DefaultConnector> {
  override val dataConnect: com.google.firebase.dataconnect.FirebaseDataConnect

  
    public val addCallLog: AddCallLogMutation
  
    public val blockNumber: BlockNumberMutation
  
    public val getBlockedNumbers: GetBlockedNumbersQuery
  
    public val getRecentCallLogs: GetRecentCallLogsQuery
  
    public val getScamNumber: GetScamNumberQuery
  
    public val getUserProfile: GetUserProfileQuery
  
    public val upsertScamNumber: UpsertScamNumberMutation
  
    public val upsertUserProfile: UpsertUserProfileMutation
  

  public companion object {
    @Suppress("MemberVisibilityCanBePrivate")
    public val config: com.google.firebase.dataconnect.ConnectorConfig = com.google.firebase.dataconnect.ConnectorConfig(
      connector = "default",
      location = "us-central1",
      serviceId = "scamshield-fdc",
    )

    public fun getInstance(
      dataConnect: com.google.firebase.dataconnect.FirebaseDataConnect
    ):DefaultConnector = synchronized(instances) {
      instances.getOrPut(dataConnect) {
        DefaultConnectorImpl(dataConnect)
      }
    }

    private val instances = java.util.WeakHashMap<com.google.firebase.dataconnect.FirebaseDataConnect, DefaultConnectorImpl>()

    
  }
}

public val DefaultConnector.Companion.instance:DefaultConnector
  get() = getInstance(com.google.firebase.dataconnect.FirebaseDataConnect._fdcGetInstance(
    config
  ))

public fun DefaultConnector.Companion.getInstance(
  settings: com.google.firebase.dataconnect.DataConnectSettings = com.google.firebase.dataconnect.DataConnectSettings()
):DefaultConnector =
  getInstance(com.google.firebase.dataconnect.FirebaseDataConnect._fdcGetInstance(config, settings))

public fun DefaultConnector.Companion.getInstance(
  app: com.google.firebase.FirebaseApp,
  settings: com.google.firebase.dataconnect.DataConnectSettings = com.google.firebase.dataconnect.DataConnectSettings()
):DefaultConnector =
  getInstance(com.google.firebase.dataconnect.FirebaseDataConnect._fdcGetInstance(app, config, settings))

private class DefaultConnectorImpl(
  override val dataConnect: com.google.firebase.dataconnect.FirebaseDataConnect
) : DefaultConnector {
  
    override val addCallLog by lazy(LazyThreadSafetyMode.PUBLICATION) {
      AddCallLogMutationImpl(this)
    }
  
    override val blockNumber by lazy(LazyThreadSafetyMode.PUBLICATION) {
      BlockNumberMutationImpl(this)
    }
  
    override val getBlockedNumbers by lazy(LazyThreadSafetyMode.PUBLICATION) {
      GetBlockedNumbersQueryImpl(this)
    }
  
    override val getRecentCallLogs by lazy(LazyThreadSafetyMode.PUBLICATION) {
      GetRecentCallLogsQueryImpl(this)
    }
  
    override val getScamNumber by lazy(LazyThreadSafetyMode.PUBLICATION) {
      GetScamNumberQueryImpl(this)
    }
  
    override val getUserProfile by lazy(LazyThreadSafetyMode.PUBLICATION) {
      GetUserProfileQueryImpl(this)
    }
  
    override val upsertScamNumber by lazy(LazyThreadSafetyMode.PUBLICATION) {
      UpsertScamNumberMutationImpl(this)
    }
  
    override val upsertUserProfile by lazy(LazyThreadSafetyMode.PUBLICATION) {
      UpsertUserProfileMutationImpl(this)
    }
  

  @com.google.firebase.dataconnect.ExperimentalFirebaseDataConnect
  override fun operations(): List<com.google.firebase.dataconnect.generated.GeneratedOperation<DefaultConnector, *, *>> =
    queries() + mutations()

  @com.google.firebase.dataconnect.ExperimentalFirebaseDataConnect
  override fun mutations(): List<com.google.firebase.dataconnect.generated.GeneratedMutation<DefaultConnector, *, *>> =
    listOf(
      addCallLog,
        blockNumber,
        upsertScamNumber,
        upsertUserProfile,
        
    )

  @com.google.firebase.dataconnect.ExperimentalFirebaseDataConnect
  override fun queries(): List<com.google.firebase.dataconnect.generated.GeneratedQuery<DefaultConnector, *, *>> =
    listOf(
      getBlockedNumbers,
        getRecentCallLogs,
        getScamNumber,
        getUserProfile,
        
    )

  @com.google.firebase.dataconnect.ExperimentalFirebaseDataConnect
  override fun copy(dataConnect: com.google.firebase.dataconnect.FirebaseDataConnect) =
    DefaultConnectorImpl(dataConnect)

  override fun equals(other: Any?): Boolean =
    other is DefaultConnectorImpl &&
    other.dataConnect == dataConnect

  override fun hashCode(): Int =
    java.util.Objects.hash(
      "DefaultConnectorImpl",
      dataConnect,
    )

  override fun toString(): String =
    "DefaultConnectorImpl(dataConnect=$dataConnect)"
}



private open class DefaultConnectorGeneratedQueryImpl<Data, Variables>(
  override val connector: DefaultConnector,
  override val operationName: String,
  override val dataDeserializer: kotlinx.serialization.DeserializationStrategy<Data>,
  override val variablesSerializer: kotlinx.serialization.SerializationStrategy<Variables>,
) : com.google.firebase.dataconnect.generated.GeneratedQuery<DefaultConnector, Data, Variables> {

  @com.google.firebase.dataconnect.ExperimentalFirebaseDataConnect
  override fun copy(
    connector: DefaultConnector,
    operationName: String,
    dataDeserializer: kotlinx.serialization.DeserializationStrategy<Data>,
    variablesSerializer: kotlinx.serialization.SerializationStrategy<Variables>,
  ) =
    DefaultConnectorGeneratedQueryImpl(
      connector, operationName, dataDeserializer, variablesSerializer
    )

  @com.google.firebase.dataconnect.ExperimentalFirebaseDataConnect
  override fun <NewVariables> withVariablesSerializer(
    variablesSerializer: kotlinx.serialization.SerializationStrategy<NewVariables>
  ) =
    DefaultConnectorGeneratedQueryImpl(
      connector, operationName, dataDeserializer, variablesSerializer
    )

  @com.google.firebase.dataconnect.ExperimentalFirebaseDataConnect
  override fun <NewData> withDataDeserializer(
    dataDeserializer: kotlinx.serialization.DeserializationStrategy<NewData>
  ) =
    DefaultConnectorGeneratedQueryImpl(
      connector, operationName, dataDeserializer, variablesSerializer
    )

  override fun equals(other: Any?): Boolean =
    other is DefaultConnectorGeneratedQueryImpl<*,*> &&
    other.connector == connector &&
    other.operationName == operationName &&
    other.dataDeserializer == dataDeserializer &&
    other.variablesSerializer == variablesSerializer

  override fun hashCode(): Int =
    java.util.Objects.hash(
      "DefaultConnectorGeneratedQueryImpl",
      connector, operationName, dataDeserializer, variablesSerializer
    )

  override fun toString(): String =
    "DefaultConnectorGeneratedQueryImpl(" +
    "operationName=$operationName, " +
    "dataDeserializer=$dataDeserializer, " +
    "variablesSerializer=$variablesSerializer, " +
    "connector=$connector)"
}

private open class DefaultConnectorGeneratedMutationImpl<Data, Variables>(
  override val connector: DefaultConnector,
  override val operationName: String,
  override val dataDeserializer: kotlinx.serialization.DeserializationStrategy<Data>,
  override val variablesSerializer: kotlinx.serialization.SerializationStrategy<Variables>,
) : com.google.firebase.dataconnect.generated.GeneratedMutation<DefaultConnector, Data, Variables> {

  @com.google.firebase.dataconnect.ExperimentalFirebaseDataConnect
  override fun copy(
    connector: DefaultConnector,
    operationName: String,
    dataDeserializer: kotlinx.serialization.DeserializationStrategy<Data>,
    variablesSerializer: kotlinx.serialization.SerializationStrategy<Variables>,
  ) =
    DefaultConnectorGeneratedMutationImpl(
      connector, operationName, dataDeserializer, variablesSerializer
    )

  @com.google.firebase.dataconnect.ExperimentalFirebaseDataConnect
  override fun <NewVariables> withVariablesSerializer(
    variablesSerializer: kotlinx.serialization.SerializationStrategy<NewVariables>
  ) =
    DefaultConnectorGeneratedMutationImpl(
      connector, operationName, dataDeserializer, variablesSerializer
    )

  @com.google.firebase.dataconnect.ExperimentalFirebaseDataConnect
  override fun <NewData> withDataDeserializer(
    dataDeserializer: kotlinx.serialization.DeserializationStrategy<NewData>
  ) =
    DefaultConnectorGeneratedMutationImpl(
      connector, operationName, dataDeserializer, variablesSerializer
    )

  override fun equals(other: Any?): Boolean =
    other is DefaultConnectorGeneratedMutationImpl<*,*> &&
    other.connector == connector &&
    other.operationName == operationName &&
    other.dataDeserializer == dataDeserializer &&
    other.variablesSerializer == variablesSerializer

  override fun hashCode(): Int =
    java.util.Objects.hash(
      "DefaultConnectorGeneratedMutationImpl",
      connector, operationName, dataDeserializer, variablesSerializer
    )

  override fun toString(): String =
    "DefaultConnectorGeneratedMutationImpl(" +
    "operationName=$operationName, " +
    "dataDeserializer=$dataDeserializer, " +
    "variablesSerializer=$variablesSerializer, " +
    "connector=$connector)"
}



private class AddCallLogMutationImpl(
  connector: DefaultConnector
):
  AddCallLogMutation,
  DefaultConnectorGeneratedMutationImpl<
      AddCallLogMutation.Data,
      AddCallLogMutation.Variables
  >(
    connector,
    AddCallLogMutation.Companion.operationName,
    AddCallLogMutation.Companion.dataDeserializer,
    AddCallLogMutation.Companion.variablesSerializer,
  )


private class BlockNumberMutationImpl(
  connector: DefaultConnector
):
  BlockNumberMutation,
  DefaultConnectorGeneratedMutationImpl<
      BlockNumberMutation.Data,
      BlockNumberMutation.Variables
  >(
    connector,
    BlockNumberMutation.Companion.operationName,
    BlockNumberMutation.Companion.dataDeserializer,
    BlockNumberMutation.Companion.variablesSerializer,
  )


private class GetBlockedNumbersQueryImpl(
  connector: DefaultConnector
):
  GetBlockedNumbersQuery,
  DefaultConnectorGeneratedQueryImpl<
      GetBlockedNumbersQuery.Data,
      GetBlockedNumbersQuery.Variables
  >(
    connector,
    GetBlockedNumbersQuery.Companion.operationName,
    GetBlockedNumbersQuery.Companion.dataDeserializer,
    GetBlockedNumbersQuery.Companion.variablesSerializer,
  )


private class GetRecentCallLogsQueryImpl(
  connector: DefaultConnector
):
  GetRecentCallLogsQuery,
  DefaultConnectorGeneratedQueryImpl<
      GetRecentCallLogsQuery.Data,
      GetRecentCallLogsQuery.Variables
  >(
    connector,
    GetRecentCallLogsQuery.Companion.operationName,
    GetRecentCallLogsQuery.Companion.dataDeserializer,
    GetRecentCallLogsQuery.Companion.variablesSerializer,
  )


private class GetScamNumberQueryImpl(
  connector: DefaultConnector
):
  GetScamNumberQuery,
  DefaultConnectorGeneratedQueryImpl<
      GetScamNumberQuery.Data,
      GetScamNumberQuery.Variables
  >(
    connector,
    GetScamNumberQuery.Companion.operationName,
    GetScamNumberQuery.Companion.dataDeserializer,
    GetScamNumberQuery.Companion.variablesSerializer,
  )


private class GetUserProfileQueryImpl(
  connector: DefaultConnector
):
  GetUserProfileQuery,
  DefaultConnectorGeneratedQueryImpl<
      GetUserProfileQuery.Data,
      Unit
  >(
    connector,
    GetUserProfileQuery.Companion.operationName,
    GetUserProfileQuery.Companion.dataDeserializer,
    GetUserProfileQuery.Companion.variablesSerializer,
  )


private class UpsertScamNumberMutationImpl(
  connector: DefaultConnector
):
  UpsertScamNumberMutation,
  DefaultConnectorGeneratedMutationImpl<
      UpsertScamNumberMutation.Data,
      UpsertScamNumberMutation.Variables
  >(
    connector,
    UpsertScamNumberMutation.Companion.operationName,
    UpsertScamNumberMutation.Companion.dataDeserializer,
    UpsertScamNumberMutation.Companion.variablesSerializer,
  )


private class UpsertUserProfileMutationImpl(
  connector: DefaultConnector
):
  UpsertUserProfileMutation,
  DefaultConnectorGeneratedMutationImpl<
      UpsertUserProfileMutation.Data,
      UpsertUserProfileMutation.Variables
  >(
    connector,
    UpsertUserProfileMutation.Companion.operationName,
    UpsertUserProfileMutation.Companion.dataDeserializer,
    UpsertUserProfileMutation.Companion.variablesSerializer,
  )


