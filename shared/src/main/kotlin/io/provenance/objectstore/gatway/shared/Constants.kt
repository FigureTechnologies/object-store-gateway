import io.grpc.Context
import io.grpc.Metadata
import java.security.PublicKey

object Constants {
    val JWT_GRPC_HEADER_KEY = Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER)
    val REQUESTOR_PUBLIC_KEY_CTX = Context.key<PublicKey>("public-key")
    val REQUESTOR_ADDRESS_CTX = Context.key<String>("requestor-address")

    // jwt claims
    val JWT_PUBLIC_KEY = "sub"
    val JWT_ADDRESS_KEY = "addr"
    val JWT_EXPIRATION_KEY = "exp"

    val MAINNET_HRP = "pb"
}
