
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


import kotlinx.coroutines.flow.filterNotNull as _flow_filterNotNull
import kotlinx.coroutines.flow.map as _flow_map


public interface GetBlockedNumbersQuery :
    com.google.firebase.dataconnect.generated.GeneratedQuery<
      DefaultConnector,
      GetBlockedNumbersQuery.Data,
      GetBlockedNumbersQuery.Variables
    >
{
  
    @kotlinx.serialization.Serializable
  public data class Variables(
  
    val blockedBy: String
  ) {
    
    
  }
  

  
    @kotlinx.serialization.Serializable
  public data class Data(
  
    val blockedNumbers: List<BlockedNumbersItem>
  ) {
    
      
        @kotlinx.serialization.Serializable
  public data class BlockedNumbersItem(
  
    val id: @kotlinx.serialization.Serializable(with = com.google.firebase.dataconnect.serializers.UUIDSerializer::class) java.util.UUID,
    val phoneNumber: String,
    val blockedBy: String,
    val reason: String,
    val scamScore: Double,
    val reportCount: Int,
    val createdAt: com.google.firebase.dataconnect.LocalDate
  ) {
    
    
  }
      
    
    
  }
  

  public companion object {
    public val operationName: String = "GetBlockedNumbers"

    public val dataDeserializer: kotlinx.serialization.DeserializationStrategy<Data> =
      kotlinx.serialization.serializer()

    public val variablesSerializer: kotlinx.serialization.SerializationStrategy<Variables> =
      kotlinx.serialization.serializer()
  }
}

public fun GetBlockedNumbersQuery.ref(
  
    blockedBy: String,

  
  
): com.google.firebase.dataconnect.QueryRef<
    GetBlockedNumbersQuery.Data,
    GetBlockedNumbersQuery.Variables
  > =
  ref(
    
      GetBlockedNumbersQuery.Variables(
        blockedBy=blockedBy,
  
      )
    
  )

public suspend fun GetBlockedNumbersQuery.execute(

  
    
      blockedBy: String,

  

  ): com.google.firebase.dataconnect.QueryResult<
    GetBlockedNumbersQuery.Data,
    GetBlockedNumbersQuery.Variables
  > =
  ref(
    
      blockedBy=blockedBy,
  
    
  ).execute()


  public fun GetBlockedNumbersQuery.flow(
    
      blockedBy: String,

  
    
    ): kotlinx.coroutines.flow.Flow<GetBlockedNumbersQuery.Data> =
    ref(
        
          blockedBy=blockedBy,
  
        
      ).subscribe()
      .flow
      ._flow_map { querySubscriptionResult -> querySubscriptionResult.result.getOrNull() }
      ._flow_filterNotNull()
      ._flow_map { it.data }

