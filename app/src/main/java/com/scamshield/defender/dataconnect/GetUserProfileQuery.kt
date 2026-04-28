
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


public interface GetUserProfileQuery :
    com.google.firebase.dataconnect.generated.GeneratedQuery<
      DefaultConnector,
      GetUserProfileQuery.Data,
      Unit
    >
{
  

  
    @kotlinx.serialization.Serializable
  public data class Data(
  
    val userProfile: UserProfile?
  ) {
    
      
        @kotlinx.serialization.Serializable
  public data class UserProfile(
  
    val id: String,
    val phoneNumber: String?,
    val email: String?,
    val country: String?,
    val createdAt: com.google.firebase.dataconnect.LocalDate
  ) {
    
    
  }
      
    
    
  }
  

  public companion object {
    public val operationName: String = "GetUserProfile"

    public val dataDeserializer: kotlinx.serialization.DeserializationStrategy<Data> =
      kotlinx.serialization.serializer()

    public val variablesSerializer: kotlinx.serialization.SerializationStrategy<Unit> =
      kotlinx.serialization.serializer()
  }
}

public fun GetUserProfileQuery.ref(
  
): com.google.firebase.dataconnect.QueryRef<
    GetUserProfileQuery.Data,
    Unit
  > =
  ref(
    
      Unit
    
  )

public suspend fun GetUserProfileQuery.execute(

  

  ): com.google.firebase.dataconnect.QueryResult<
    GetUserProfileQuery.Data,
    Unit
  > =
  ref(
    
  ).execute()


  public fun GetUserProfileQuery.flow(
    
    ): kotlinx.coroutines.flow.Flow<GetUserProfileQuery.Data> =
    ref(
        
      ).subscribe()
      .flow
      ._flow_map { querySubscriptionResult -> querySubscriptionResult.result.getOrNull() }
      ._flow_filterNotNull()
      ._flow_map { it.data }

