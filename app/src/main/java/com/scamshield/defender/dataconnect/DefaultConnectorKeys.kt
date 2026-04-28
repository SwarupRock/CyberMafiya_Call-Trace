
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


  @kotlinx.serialization.Serializable
  public data class BlockedNumberKey(
  
    val id: @kotlinx.serialization.Serializable(with = com.google.firebase.dataconnect.serializers.UUIDSerializer::class) java.util.UUID
  ) {
    
    
  }

  @kotlinx.serialization.Serializable
  public data class CallLogKey(
  
    val id: @kotlinx.serialization.Serializable(with = com.google.firebase.dataconnect.serializers.UUIDSerializer::class) java.util.UUID
  ) {
    
    
  }

  @kotlinx.serialization.Serializable
  public data class ScamNumberKey(
  
    val id: String
  ) {
    
    
  }

  @kotlinx.serialization.Serializable
  public data class UserProfileKey(
  
    val id: String
  ) {
    
    
  }

