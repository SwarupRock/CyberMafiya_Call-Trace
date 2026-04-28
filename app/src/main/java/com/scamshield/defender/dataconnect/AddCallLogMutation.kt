
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



public interface AddCallLogMutation :
    com.google.firebase.dataconnect.generated.GeneratedMutation<
      DefaultConnector,
      AddCallLogMutation.Data,
      AddCallLogMutation.Variables
    >
{
  
    @kotlinx.serialization.Serializable
  public data class Variables(
  
    val phoneNumber: String,
    val callDurationSeconds: Int,
    val scamScore: Double,
    val isScam: Boolean,
    val deepfakeScore: Double
  ) {
    
    
  }
  

  
    @kotlinx.serialization.Serializable
  public data class Data(
  
    val callLog_insert: CallLogKey
  ) {
    
    
  }
  

  public companion object {
    public val operationName: String = "AddCallLog"

    public val dataDeserializer: kotlinx.serialization.DeserializationStrategy<Data> =
      kotlinx.serialization.serializer()

    public val variablesSerializer: kotlinx.serialization.SerializationStrategy<Variables> =
      kotlinx.serialization.serializer()
  }
}

public fun AddCallLogMutation.ref(
  
    phoneNumber: String,callDurationSeconds: Int,scamScore: Double,isScam: Boolean,deepfakeScore: Double,

  
  
): com.google.firebase.dataconnect.MutationRef<
    AddCallLogMutation.Data,
    AddCallLogMutation.Variables
  > =
  ref(
    
      AddCallLogMutation.Variables(
        phoneNumber=phoneNumber,callDurationSeconds=callDurationSeconds,scamScore=scamScore,isScam=isScam,deepfakeScore=deepfakeScore,
  
      )
    
  )

public suspend fun AddCallLogMutation.execute(

  
    
      phoneNumber: String,callDurationSeconds: Int,scamScore: Double,isScam: Boolean,deepfakeScore: Double,

  

  ): com.google.firebase.dataconnect.MutationResult<
    AddCallLogMutation.Data,
    AddCallLogMutation.Variables
  > =
  ref(
    
      phoneNumber=phoneNumber,callDurationSeconds=callDurationSeconds,scamScore=scamScore,isScam=isScam,deepfakeScore=deepfakeScore,
  
    
  ).execute()


