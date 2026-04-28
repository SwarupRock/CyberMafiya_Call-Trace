
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



public interface UpsertUserProfileMutation :
    com.google.firebase.dataconnect.generated.GeneratedMutation<
      DefaultConnector,
      UpsertUserProfileMutation.Data,
      UpsertUserProfileMutation.Variables
    >
{
  
    @kotlinx.serialization.Serializable
  public data class Variables(
  
    val phoneNumber: com.google.firebase.dataconnect.OptionalVariable<String?>,
    val email: com.google.firebase.dataconnect.OptionalVariable<String?>,
    val country: com.google.firebase.dataconnect.OptionalVariable<String?>
  ) {
    
    
      
      @kotlin.DslMarker public annotation class BuilderDsl

      @BuilderDsl
      public interface Builder {
        public var phoneNumber: String?
        public var email: String?
        public var country: String?
        
      }

      public companion object {
        @Suppress("NAME_SHADOWING")
        public fun build(
          
          block_: Builder.() -> Unit
        ): Variables {
          var phoneNumber: com.google.firebase.dataconnect.OptionalVariable<String?> =
                com.google.firebase.dataconnect.OptionalVariable.Undefined
            var email: com.google.firebase.dataconnect.OptionalVariable<String?> =
                com.google.firebase.dataconnect.OptionalVariable.Undefined
            var country: com.google.firebase.dataconnect.OptionalVariable<String?> =
                com.google.firebase.dataconnect.OptionalVariable.Undefined
            

          return object : Builder {
            override var phoneNumber: String?
              get() = throw UnsupportedOperationException("getting builder values is not supported")
              set(value_) { phoneNumber = com.google.firebase.dataconnect.OptionalVariable.Value(value_) }
              
            override var email: String?
              get() = throw UnsupportedOperationException("getting builder values is not supported")
              set(value_) { email = com.google.firebase.dataconnect.OptionalVariable.Value(value_) }
              
            override var country: String?
              get() = throw UnsupportedOperationException("getting builder values is not supported")
              set(value_) { country = com.google.firebase.dataconnect.OptionalVariable.Value(value_) }
              
            
          }.apply(block_)
          .let {
            Variables(
              phoneNumber=phoneNumber,email=email,country=country,
            )
          }
        }
      }
    
  }
  

  
    @kotlinx.serialization.Serializable
  public data class Data(
  
    val userProfile_upsert: UserProfileKey
  ) {
    
    
  }
  

  public companion object {
    public val operationName: String = "UpsertUserProfile"

    public val dataDeserializer: kotlinx.serialization.DeserializationStrategy<Data> =
      kotlinx.serialization.serializer()

    public val variablesSerializer: kotlinx.serialization.SerializationStrategy<Variables> =
      kotlinx.serialization.serializer()
  }
}

public fun UpsertUserProfileMutation.ref(
  
    

  
    block_: UpsertUserProfileMutation.Variables.Builder.() -> Unit = {}
  
): com.google.firebase.dataconnect.MutationRef<
    UpsertUserProfileMutation.Data,
    UpsertUserProfileMutation.Variables
  > =
  ref(
    
      UpsertUserProfileMutation.Variables.build(
        
  
    block_
      )
    
  )

public suspend fun UpsertUserProfileMutation.execute(

  
    
      

  
    block_: UpsertUserProfileMutation.Variables.Builder.() -> Unit = {}

  ): com.google.firebase.dataconnect.MutationResult<
    UpsertUserProfileMutation.Data,
    UpsertUserProfileMutation.Variables
  > =
  ref(
    
      
  
    block_
    
  ).execute()


