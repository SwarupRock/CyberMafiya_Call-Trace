
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



public interface UpsertScamNumberMutation :
    com.google.firebase.dataconnect.generated.GeneratedMutation<
      DefaultConnector,
      UpsertScamNumberMutation.Data,
      UpsertScamNumberMutation.Variables
    >
{
  
    @kotlinx.serialization.Serializable
  public data class Variables(
  
    val phoneNumber: String,
    val totalReports: Int,
    val avgScamScore: Double,
    val isVerifiedScam: Boolean
  ) {
    
    
  }
  

  
    @kotlinx.serialization.Serializable
  public data class Data(
  
    val scamNumber_upsert: ScamNumberKey
  ) {
    
    
  }
  

  public companion object {
    public val operationName: String = "UpsertScamNumber"

    public val dataDeserializer: kotlinx.serialization.DeserializationStrategy<Data> =
      kotlinx.serialization.serializer()

    public val variablesSerializer: kotlinx.serialization.SerializationStrategy<Variables> =
      kotlinx.serialization.serializer()
  }
}

public fun UpsertScamNumberMutation.ref(
  
    phoneNumber: String,totalReports: Int,avgScamScore: Double,isVerifiedScam: Boolean,

  
  
): com.google.firebase.dataconnect.MutationRef<
    UpsertScamNumberMutation.Data,
    UpsertScamNumberMutation.Variables
  > =
  ref(
    
      UpsertScamNumberMutation.Variables(
        phoneNumber=phoneNumber,totalReports=totalReports,avgScamScore=avgScamScore,isVerifiedScam=isVerifiedScam,
  
      )
    
  )

public suspend fun UpsertScamNumberMutation.execute(

  
    
      phoneNumber: String,totalReports: Int,avgScamScore: Double,isVerifiedScam: Boolean,

  

  ): com.google.firebase.dataconnect.MutationResult<
    UpsertScamNumberMutation.Data,
    UpsertScamNumberMutation.Variables
  > =
  ref(
    
      phoneNumber=phoneNumber,totalReports=totalReports,avgScamScore=avgScamScore,isVerifiedScam=isVerifiedScam,
  
    
  ).execute()


