package tech.figure.objectstore.gateway.helpers

import com.google.protobuf.Message
import io.grpc.StatusRuntimeException
import io.grpc.stub.StreamObserver
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot

/**
 * Observer mocks will freak out and throw an exception when any of their standard response functions are called.
 * This function will inline create a StreamObserver for the rpc message requested, ensuring that all its relevant
 * functions utilized in this application are mocked out.
 */
inline fun <reified T : Message> mockkObserver(): StreamObserver<T> = mockk<StreamObserver<T>>().also { observer ->
    every { observer.onNext(any()) } returns Unit
    every { observer.onError(any()) } returns Unit
    every { observer.onCompleted() } returns Unit
}

/**
 * Generates a slot designed for capturing output when a StreamObserver intends to produce an error.
 */
inline fun <reified S : StatusRuntimeException> StreamObserver<*>.createErrorSlot(): CapturingSlot<S> = this.let { observer ->
    slot<S>().also { exceptionSlot ->
        every { observer.onError(capture(exceptionSlot)) } returns Unit
    }
}
