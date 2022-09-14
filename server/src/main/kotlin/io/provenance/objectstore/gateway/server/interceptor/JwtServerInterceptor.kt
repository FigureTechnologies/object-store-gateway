package io.provenance.objectstore.gateway.server.interceptor

import io.grpc.Context
import io.grpc.Contexts
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status
import io.provenance.objectstore.gateway.service.JwtVerificationService
import org.springframework.stereotype.Service

@Service
class JwtServerInterceptor(
    private val verifier: JwtVerificationService
) : ServerInterceptor {
    override fun <ReqT : Any?, RespT : Any?> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>
    ): ServerCall.Listener<ReqT> {
        val jwtString = headers[Constants.JWT_GRPC_HEADER_KEY]
        if (jwtString == null) {
            call.close(Status.UNAUTHENTICATED.withDescription("JWT is missing from Metadata"), headers)
            return object : ServerCall.Listener<ReqT>() {}
        }

        val verificationResult = verifier.verifyJwtString(jwtString)

        val context = Context.current()
            // set up context values for easy retrieval
            .withValue(Constants.REQUESTOR_PUBLIC_KEY_CTX, verificationResult.publicKey)
            .withValue(Constants.REQUESTOR_ADDRESS_CTX, verificationResult.address)

        return Contexts.interceptCall(context, call, headers, next)
    }
}
