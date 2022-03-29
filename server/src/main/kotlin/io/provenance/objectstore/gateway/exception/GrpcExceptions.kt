package io.provenance.objectstore.gateway.exception

import io.grpc.Status
import io.grpc.StatusRuntimeException

class AccessDeniedException: StatusRuntimeException(Status.PERMISSION_DENIED)
class SignatureValidationException: StatusRuntimeException(Status.PERMISSION_DENIED)
class TimestampValidationException: StatusRuntimeException(Status.PERMISSION_DENIED)
