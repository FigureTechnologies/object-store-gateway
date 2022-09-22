package tech.figure.objectstore.gateway.helpers

import io.provenance.hdwallet.bip39.MnemonicWords
import io.provenance.hdwallet.ec.extensions.toJavaECKeyPair
import io.provenance.hdwallet.wallet.Account
import io.provenance.hdwallet.wallet.Wallet
import io.provenance.scope.encryption.model.DirectKeyRef
import io.provenance.scope.encryption.model.KeyRef

fun genRandomAccount(): Account = Wallet.fromMnemonic(
    hrp = "tp",
    passphrase = "",
    mnemonicWords = MnemonicWords.generate(strength = 256),
    testnet = true,
)["m/44'/1'/0'/0/0'"]

val Account.bech32Address: String
    get() = address.value

val Account.keyRef: KeyRef
    get() = keyPair.toJavaECKeyPair().let(::DirectKeyRef)
