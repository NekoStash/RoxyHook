package hk.uwu.roxyhook

enum class CallbackErrorPolicy { LOG_AND_CONTINUE, PROPAGATE }
data class RoxyConfig(val callbackErrorPolicy: CallbackErrorPolicy = CallbackErrorPolicy.LOG_AND_CONTINUE)
