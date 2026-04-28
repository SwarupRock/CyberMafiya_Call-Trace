
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


public interface GetScamNumberQuery :
    com.google.firebase.dataconnect.generated.GeneratedQuery<
      DefaultConnector,
      GetScamNumberQuery.Data,
      GetScamNumberQuery.Variables
    >
{
  
    @kotlinx.serialization.Serializable
  public data class Variables(
  
    val phoneNumber: String
  ) {
    
    
  }
  

  
    @kotlinx.serialization.Serializable
  public data class Data(
  
    val scamNumber: ScamNumber?
  ) {
    
      
        @kotlinx.serialization.Serializable
  public data class ScamNumber(
  
    val id: String,
    val totalReports: Int,
    val avgScamScore: Double,
    val firstReported: com.google.firebase.dataconnect.LocalDate,
    val lastReported: com.google.firebase.dataconnect.LocalDate,
    val isVerifiedScam: Boolean
  ) {
    
    
  }
      
    
    
  }
  

  public companion object {
    public val operationName: String = "GetScamNumber"

    public val dataDeserializer: kotlinx.serialization.DeserializationStrategy<Data> =
      kotlinx.serialization.serializer()

    public val variablesSerializer: kotlinx.serialization.SerializationStrategy<Variables> =
      kotlinx.serialization.serializer()
  }
}

public fun GetScamNumberQuery.ref(
  
    phoneNumber: String,

  
  
): com.google.firebase.dataconnect.QueryRef<
    GetScamNumberQuery.Data,
    GetScamNumberQuery.Variables
  > =
  ref(
    
      GetScamNumberQuery.Variables(
        phoneNumber=phoneNumber,
  
      )
    
  )

public suspend fun GetScamNumberQuery.execute(

  
    
      phoneNumber: String,

  

  ): com.google.firebase.dataconnect.QueryResult<
    GetScamNumberQuery.Data,
    GetScamNumberQuery.Variables
  > =
  ref(
    
      phoneNumber=phoneNumber,
  
    
  ).execute()


  public fun GetScamNumberQuery.flow(
    
      phoneNumber: String,

  
    
    ): kotlinx.coroutines.flow.Flow<GetScamNumberQuery.Data> =
    ref(
        
          phoneNumber=phoneNumber,
  
        
      ).subscribe()
      .flow
      ._flow_map { querySubscriptionResult -> querySubscriptionResult.result.getOrNull() }
      ._flow_filterNotNull()
      ._flow_map { it.data }

