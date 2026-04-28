
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



public interface BlockNumberMutation :
    com.google.firebase.dataconnect.generated.GeneratedMutation<
      DefaultConnector,
      BlockNumberMutation.Data,
      BlockNumberMutation.Variables
    >
{
  
    @kotlinx.serialization.Serializable
  public data class Variables(
  
    val phoneNumber: String,
    val reason: String,
    val scamScore: Double
  ) {
    
    
  }
  

  
    @kotlinx.serialization.Serializable
  public data class Data(
  
    val blockedNumber_insert: BlockedNumberKey
  ) {
    
    
  }
  

  public companion object {
    public val operationName: String = "BlockNumber"

    public val dataDeserializer: kotlinx.serialization.DeserializationStrategy<Data> =
      kotlinx.serialization.serializer()

    public val variablesSerializer: kotlinx.serialization.SerializationStrategy<Variables> =
      kotlinx.serialization.serializer()
  }
}

public fun BlockNumberMutation.ref(
  
    phoneNumber: String,reason: String,scamScore: Double,

  
  
): com.google.firebase.dataconnect.MutationRef<
    BlockNumberMutation.Data,
    BlockNumberMutation.Variables
  > =
  ref(
    
      BlockNumberMutation.Variables(
        phoneNumber=phoneNumber,reason=reason,scamScore=scamScore,
  
      )
    
  )

public suspend fun BlockNumberMutation.execute(

  
    
      phoneNumber: String,reason: String,scamScore: Double,

  

  ): com.google.firebase.dataconnect.MutationResult<
    BlockNumberMutation.Data,
    BlockNumberMutation.Variables
  > =
  ref(
    
      phoneNumber=phoneNumber,reason=reason,scamScore=scamScore,
  
    
  ).execute()


