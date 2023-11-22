import com.google.gson.JsonParser
import com.google.protobuf.Any
import com.google.protobuf.ByteString
import com.google.protobuf.Message
import cosmos.base.v1beta1.CoinOuterClass
import cosmos.crypto.secp256k1.Keys
import cosmos.tx.v1beta1.ServiceOuterClass
import cosmos.tx.v1beta1.TxOuterClass
import cosmwasm.wasm.v1.QueryOuterClass
import cosmwasm.wasm.v1.Tx
import io.provenance.client.grpc.BaseReqSigner
import io.provenance.client.grpc.GasEstimationMethod
import io.provenance.client.grpc.PbClient
import io.provenance.client.grpc.Signer
import io.provenance.client.protobuf.extensions.getBaseAccount
import io.provenance.client.protobuf.extensions.queryWasm
import io.provenance.hdwallet.bip39.MnemonicWords
import io.provenance.hdwallet.ec.extensions.toJavaECKeyPair
import io.provenance.hdwallet.ec.extensions.toJavaECPrivateKey
import io.provenance.hdwallet.signer.BCECSigner
import io.provenance.hdwallet.wallet.Account
import io.provenance.hdwallet.wallet.Wallet
import io.provenance.metadata.v1.ContractSpecificationRequest
import io.provenance.metadata.v1.MsgWriteRecordRequest
import io.provenance.metadata.v1.MsgWriteScopeRequest
import io.provenance.metadata.v1.MsgWriteSessionRequest
import io.provenance.metadata.v1.Party
import io.provenance.metadata.v1.PartyType
import io.provenance.metadata.v1.Process
import io.provenance.metadata.v1.Record
import io.provenance.metadata.v1.RecordInput
import io.provenance.metadata.v1.RecordInputStatus
import io.provenance.metadata.v1.RecordOutput
import io.provenance.metadata.v1.ResultStatus
import io.provenance.metadata.v1.Scope
import io.provenance.metadata.v1.ScopeSpecificationRequest
import io.provenance.metadata.v1.Session
import io.provenance.metadata.v1.SessionIdComponents
import io.provenance.name.v1.QueryResolveRequest
import io.provenance.scope.encryption.model.DirectKeyRef
import io.provenance.scope.encryption.util.toHex
import io.provenance.scope.encryption.util.toKeyPair
import io.provenance.scope.objectstore.client.CachedOsClient
import io.provenance.scope.objectstore.client.OsClient
import io.provenance.scope.util.MetadataAddress
import io.provenance.scope.util.sha256
import io.provenance.scope.util.toByteString
import tech.figure.objectstore.gateway.client.ClientConfig
import tech.figure.objectstore.gateway.client.GatewayClient
import org.bouncycastle.jce.provider.BouncyCastleProvider
import tech.figure.objectstore.gateway.client.GatewayJwt
import tech.figure.proto.util.toProtoUUID
import java.net.URI
import java.security.Security
import java.util.UUID

enum class NetworkType(
    /**
     * The hrp (Human Readable Prefix) of the network address
     */
    val prefix: String,
    /**
     * The HD wallet path
     */
    val path: String
) {
    TESTNET("tp", "m/44'/1'/0'/0/0"),
    TESTNET_HARDENED("tp", "m/44'/1'/0'/0/0'"),
    MAINNET("pb", "m/44'/505'/0'/0/0")
}

class WalletSigner(networkType: NetworkType, mnemonic: String, passphrase: String = "") : Signer {

    val wallet = Wallet.fromMnemonic(networkType.prefix, passphrase.toCharArray(), MnemonicWords.of(mnemonic))

    val account: Account = wallet[networkType.path]

    override fun address(): String = account.address.value

    override fun pubKey(): Keys.PubKey =
        Keys.PubKey.newBuilder().setKey(ByteString.copyFrom(account.keyPair.publicKey.compressed())).build()

    override fun sign(data: ByteArray): ByteArray = BCECSigner()
        .sign(account.keyPair.privateKey, data.sha256())
        .encodeAsBTC().toByteArray()
}

fun Message.toAny() = Any.pack(this, "")

fun main() {
    val client = PbClient("pio-testnet-1", URI("grpcs://grpc.test.provenance.io:443"), GasEstimationMethod.MSG_FEE_CALCULATION)
    val osClient = CachedOsClient(OsClient(URI("grpc://localhost:5005"), 30_000), 1, 1, 1, 1)

    val scopeUuid = UUID.randomUUID()
    val scopeAddress = MetadataAddress.forScope(scopeUuid).toString()
    println("Scope uuid $scopeUuid (address: $scopeAddress)")
    val contractName = "testassets.pb"
    val assetType = "collectible"
//    val verifierAddress = "tp15e6l9dv8s2rdshjfn34k8a2nju55tr4z42phrt"

    val contractAddress = client.nameClient.resolve(QueryResolveRequest.newBuilder().setName(contractName).build()).address
    println("contractAddress: $contractAddress")

    val (scopeSpecificationAddress, verifierAddress) = client.wasmClient.queryWasm(QueryOuterClass.QuerySmartContractStateRequest.newBuilder()
        .setAddress(contractAddress)
        .setQueryData("""{"query_asset_definition": {"qualifier": {"type": "asset_type", "value": "$assetType"}}}""".toByteString())
        .build()
    ).data.toStringUtf8().let { JsonParser.parseString(it) }.asJsonObject.let {
        it.get("scope_spec_address").asString to it.get("verifiers").asJsonArray.first().asJsonObject.get("address").asString
    }
    val scopeSpecUuid = MetadataAddress.fromBech32(scopeSpecificationAddress).getPrimaryUuid()
    println("scopeSpecificationAddress for $assetType: $scopeSpecificationAddress (uuid: $scopeSpecUuid)")
    println("verifier address = $verifierAddress")

    val scopeSpec = client.metadataClient.scopeSpecification(ScopeSpecificationRequest.newBuilder().setSpecificationId(scopeSpecificationAddress).build())
    val contractSpecUuid = scopeSpec.scopeSpecification.specification.contractSpecIdsList.first().let { MetadataAddress.fromBytes(it.toByteArray()).getPrimaryUuid() }
    println("contractSpecUuid = $contractSpecUuid")
    val contractSpec = client.metadataClient.contractSpecification(ContractSpecificationRequest.newBuilder().setSpecificationId(contractSpecUuid.toString()).setIncludeRecordSpecs(true).build())
    val recordSpec = contractSpec.recordSpecificationsList.first()
    val recordName = recordSpec.specification.name

    println("enter your mnemonic:")
    val mnemonic = readLine()!!

    val signer = WalletSigner(NetworkType.TESTNET_HARDENED, mnemonic)
    println("signer address is ${signer.address()}")
    val balance = client.bankClient.balance(cosmos.bank.v1beta1.QueryOuterClass.QueryBalanceRequest.newBuilder().setAddress(signer.address()).setDenom("nhash").build()).balance
    println("signer balance is ${balance.amount}${balance.denom}")

    Security.addProvider(BouncyCastleProvider())
    val privateKey = signer.account.keyPair.privateKey.toJavaECPrivateKey().also {
        println("corresponding hex private key is ${it.toHex()}")
    }
    val record = tech.figure.asset.v1beta1.Asset.newBuilder()
        .setDescription("cool asset")
        .setId(UUID.randomUUID().toProtoUUID())
        .build()
    val hash = osClient.putRecord(record, DirectKeyRef(privateKey.toKeyPair()), DirectKeyRef(privateKey.toKeyPair())).get().value
    osClient.osClient.close()

    val writeScopeMsg = MsgWriteScopeRequest.newBuilder()
        .setScopeUuid(scopeUuid.toString())
        .setSpecUuid(scopeSpecUuid.toString())
        .addSigners(signer.address())
        .setScope(Scope.newBuilder()
            .setValueOwnerAddress(signer.address())
            .addOwners(Party.newBuilder().setRole(PartyType.PARTY_TYPE_OWNER).setAddress(signer.address()))
        ).build().toAny()

    val sessionUuid = UUID.randomUUID()
    val writeSessionMsg = MsgWriteSessionRequest.newBuilder()
        .setSession(Session.newBuilder()
            .setSessionId(MetadataAddress.forSession(scopeUuid, sessionUuid).bytes.toByteString())
            .setName("totally cool session")
            .addParties(Party.newBuilder().setRole(PartyType.PARTY_TYPE_OWNER).setAddress(signer.address()))
        )
        .addSigners(signer.address())
        .setSpecUuid(contractSpecUuid.toString())
        .build().toAny()

    val writeRecordMsg = MsgWriteRecordRequest.newBuilder()
        .setRecord(Record.newBuilder()
            .setName(recordName)
            .addAllInputs(recordSpec.specification.inputsList.map {
                RecordInput.newBuilder()
                    .setName(it.name)
                    .setTypeName(it.typeName)
                    .setHash(hash)
                    .setStatus(RecordInputStatus.RECORD_INPUT_STATUS_PROPOSED)
                    .build()
            })
            .addOutputs(RecordOutput.newBuilder()
                .setStatus(ResultStatus.RESULT_STATUS_PASS)
                .setHash(hash)
            ).setProcess(Process.newBuilder()
                .setName(recordSpec.specification.typeName)
                .setHash("some contract hash")
                .setMethod(recordSpec.specification.name)
            )
        )
        .addParties(Party.newBuilder().setRole(PartyType.PARTY_TYPE_OWNER).setAddress(signer.address()))
        .setContractSpecUuid(contractSpecUuid.toString())
        .addSigners(signer.address())
        .setSessionIdComponents(SessionIdComponents.newBuilder().setScopeUuid(scopeUuid.toString()).setSessionUuid(sessionUuid.toString()))
        .build().toAny()
    val onboardAssetMessage = Tx.MsgExecuteContract.newBuilder()
        .setContract(contractAddress)
        .setMsg("""{"onboard_asset": {"identifier": {"type": "asset_uuid", "value": "$scopeUuid"}, "asset_type": "$assetType", "verifier_address": "$verifierAddress"}}""".toByteString())
        .addFunds(CoinOuterClass.Coin.newBuilder().setDenom("nhash").setAmount("10"))
        .setSender(signer.address())
        .build().toAny()

    val account = client.authClient.getBaseAccount(signer.address())

    val scopeRes = client.estimateAndBroadcastTx(TxOuterClass.TxBody.newBuilder()
        .addAllMessages(listOf(
            writeScopeMsg,
            writeSessionMsg,
            writeRecordMsg,
        )).build(), listOf(BaseReqSigner(signer, 0, account)), ServiceOuterClass.BroadcastMode.BROADCAST_MODE_BLOCK, gasAdjustment = 3.0)

    println("ScopeRes = $scopeRes")

    val onboardRes = client.estimateAndBroadcastTx(TxOuterClass.TxBody.newBuilder()
        .addAllMessages(listOf(
            onboardAssetMessage,
        )).build(), listOf(BaseReqSigner(signer, 1, account)), ServiceOuterClass.BroadcastMode.BROADCAST_MODE_BLOCK, gasAdjustment = 3.0)

    println("onboardRes = $onboardRes")

    println("enter validator mnemonic")
    val validatorMnemonic = readLine()!!

    val validatorSigner = WalletSigner(NetworkType.TESTNET, validatorMnemonic)
    println("validator address from mnemonic: ${validatorSigner.address()}")

    GatewayClient(
        ClientConfig(
            URI("grpc://localhost:8080"),
            false
        )
    ).use { gatewayClient ->
        while (true) {
            val jwt = GatewayJwt.KeyPairJwt(validatorSigner.account.keyPair.toJavaECKeyPair())
            try {
                val response = gatewayClient.requestScopeData(scopeAddress, jwt)

                println("Fetched records $response")
                break
            } catch (e: Exception) {
                println("Exception fetching records: ${e.message}")
                Thread.sleep(5000)
            }

        }
    }

}
