package io.provenance.objectstore.gateway.exception

import io.grpc.Status
import io.grpc.StatusRuntimeException

class AccessDeniedException(message: String?): StatusRuntimeException(Status.PERMISSION_DENIED.withDescription(message))
class JwtValidationException(message: String?): StatusRuntimeException(Status.PERMISSION_DENIED.withDescription(message))
class SignatureValidationException(message: String?): StatusRuntimeException(Status.PERMISSION_DENIED.withDescription(message))
class TimestampValidationException(message: String?): StatusRuntimeException(Status.PERMISSION_DENIED.withDescription(message))
