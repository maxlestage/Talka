// Le Keystore d'Android : ce que le trousseau est à l'iPhone. Il garde la
// clé de l'appareil (`StoreVault`, `KeyGuard`) et les secrets de la v3 et de
// Brocolis (`V3Vault`, `BrocolisVault`, `SecretGuard`), pour cet appareil
// seulement : rien ne part dans les sauvegardes du système
// (`data_extraction_rules.xml`).
//
// Le Keystore ne range pas de données, seulement des clés qui n'en sortent
// jamais. Chaque copie est donc chiffrée par une clé du Keystore et rangée,
// chiffrée, dans les préférences privées de l'application :
//
// - `plain` (lisible par Talka seule, en arrière-plan) : AES-256-GCM, une
//   clé du Keystore sans authentification ;
// - `presence` (biométrie ou code de l'appareil) et `biometry` (une
//   empreinte ou un visage enregistré, invalidée dès qu'on en ajoute un) :
//   RSA-2048 OAEP. Chiffrer ne demande que la clé publique (écrire une copie
//   se fait sans authentification, comme sur l'iPhone) ; déchiffrer demande
//   la clé privée, ouverte par l'utilisateur à chaque fois. L'écran prépare
//   le déchiffrement (`unlockCipher`), le fait signer par `BiometricPrompt`
//   (un `CryptoObject`), et passe ce `Cipher` authentifié en `auth`.

package talka.app.sys

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import talka.kit.KeyGuard
import talka.kit.SecretGuard
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

class KeystoreGuard(context: Context) : KeyGuard, SecretGuard {
    private val prefs: SharedPreferences = context.getSharedPreferences("talka.garde", Context.MODE_PRIVATE)
    private val flags: SharedPreferences = context.getSharedPreferences("talka.reglages", Context.MODE_PRIVATE)
    private val store: KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    // --- La clé de l'appareil (`KeyGuard`) ---

    override fun read(account: String, auth: Any?): KeyGuard.Read = readItem("cle:$account", auth)

    override fun add(account: String, data: ByteArray, access: KeyGuard.Access): Boolean = writeItem("cle:$account", data, access)

    override fun delete(account: String) {
        prefs.edit().remove("cle:$account").commit()
    }

    override fun flag(name: String): Boolean = flags.getBoolean(name, false)

    override fun setFlag(name: String, on: Boolean) {
        flags.edit().putBoolean(name, on).commit()
    }

    override fun removeFlag(name: String) {
        flags.edit().remove(name).commit()
    }

    // --- Les secrets de la v3 et de Brocolis (`SecretGuard`) ---

    override fun read(service: String, account: String): KeyGuard.Read = readItem(secretName(service, account), null)

    override fun write(service: String, account: String, data: ByteArray): Boolean =
        writeItem(secretName(service, account), data, KeyGuard.Access.plain)

    override fun delete(service: String, account: String) {
        prefs.edit().remove(secretName(service, account)).commit()
    }

    override fun clear(service: String) {
        val prefix = secretName(service, "")
        val edit = prefs.edit()
        for (name in prefs.all.keys) if (name.startsWith(prefix)) edit.remove(name)
        edit.commit()
    }

    private fun secretName(service: String, account: String) = "secret:$service:$account"

    // --- L'authentification ---

    /**
     * Le déchiffrement d'une copie protégée de la clé de l'appareil, à faire
     * signer par `BiometricPrompt` (`CryptoObject(cipher)`) ; une fois signé,
     * il se passe en `auth` à `StoreVault.key(auth)` ou `keyByFace(auth)`.
     * Rien si la copie n'est pas protégée, n'existe pas, ou si la clé a été
     * invalidée (une empreinte ajoutée depuis).
     */
    fun unlockCipher(account: String): Cipher? {
        val item = load("cle:$account") ?: return null
        if (item.access == KeyGuard.Access.plain) return null
        return try {
            val key = store.getKey(alias(item.access), null) as? PrivateKey ?: return null
            Cipher.getInstance(RSA).apply { init(Cipher.DECRYPT_MODE, key, OAEP) }
        } catch (e: KeyPermanentlyInvalidatedException) {
            null
        } catch (e: GeneralSecurityException) {
            null
        }
    }

    // --- Les copies ---

    private class Item(val access: KeyGuard.Access, val iv: ByteArray, val sealed: ByteArray)

    /** Une copie rangée : « accès:iv:chiffré » (le tout en base64). */
    private fun load(name: String): Item? {
        val parts = prefs.getString(name, null)?.split(':') ?: return null
        if (parts.size != 3) return null
        val access = KeyGuard.Access.entries.firstOrNull { it.name == parts[0] } ?: return null
        return try {
            Item(access, b64(parts[1]), b64(parts[2]))
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun readItem(name: String, auth: Any?): KeyGuard.Read {
        val item = load(name) ?: return KeyGuard.Read.NotFound
        return try {
            val data = when (item.access) {
                KeyGuard.Access.plain -> {
                    val key = store.getKey(alias(item.access), null) as? SecretKey ?: return KeyGuard.Read.Failed
                    Cipher.getInstance(AES).run {
                        init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, item.iv))
                        updateAAD(name.toByteArray())
                        doFinal(item.sealed)
                    }
                }
                // Déchiffré par le `Cipher` que l'utilisateur a ouvert.
                else -> (auth as? Cipher ?: return KeyGuard.Read.Failed).doFinal(item.sealed)
            }
            KeyGuard.Read.Found(data)
        } catch (e: GeneralSecurityException) {
            // Verrouillée, refusée, invalidée : surtout ne rien écraser.
            KeyGuard.Read.Failed
        } catch (e: IllegalStateException) {
            KeyGuard.Read.Failed
        }
    }

    private fun writeItem(name: String, data: ByteArray, access: KeyGuard.Access): Boolean =
        try {
            val (iv, sealed) = when (access) {
                KeyGuard.Access.plain -> Cipher.getInstance(AES).run {
                    init(Cipher.ENCRYPT_MODE, aesKey())
                    updateAAD(name.toByteArray())
                    val out = doFinal(data)
                    iv to out
                }
                else -> {
                    // La clé publique hors du Keystore : chiffrer sans authentification.
                    val public = rsaPublic(access)
                    val plainPublic = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(public.encoded))
                    ByteArray(0) to Cipher.getInstance(RSA).run {
                        init(Cipher.ENCRYPT_MODE, plainPublic, OAEP)
                        doFinal(data)
                    }
                }
            }
            prefs.edit().putString(name, "${access.name}:${b64(iv)}:${b64(sealed)}").commit()
        } catch (e: GeneralSecurityException) {
            false
        } catch (e: IllegalStateException) {
            false
        }

    // --- Les clés du Keystore ---

    private fun alias(access: KeyGuard.Access) = "talka.garde.${access.name}"

    private fun aesKey(): SecretKey {
        (store.getKey(alias(KeyGuard.Access.plain), null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(alias(KeyGuard.Access.plain), KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(spec)
            generateKey()
        }
    }

    /** La clé publique d'une paire RSA dont la moitié privée s'ouvre par l'utilisateur. */
    private fun rsaPublic(access: KeyGuard.Access): java.security.PublicKey {
        val alias = alias(access)
        store.getCertificate(alias)?.publicKey?.let { pub ->
            // Invalidée (une empreinte ajoutée) : une nouvelle paire.
            if (usable(alias)) return pub
            store.deleteEntry(alias)
        }
        val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_DECRYPT or KeyProperties.PURPOSE_ENCRYPT)
            .setKeySize(2048)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
            .setUserAuthenticationRequired(true)
        if (access == KeyGuard.Access.biometry) builder.setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val kinds = if (access == KeyGuard.Access.presence) {
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
            } else {
                KeyProperties.AUTH_BIOMETRIC_STRONG
            }
            builder.setUserAuthenticationParameters(0, kinds)
        } else {
            // Avant Android 11 : la biométrie, à chaque déchiffrement.
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }
        return KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, KEYSTORE).run {
            initialize(builder.build())
            generateKeyPair().public
        }
    }

    /** La moitié privée s'ouvre-t-elle encore (pas invalidée par une nouvelle empreinte) ? */
    private fun usable(alias: String): Boolean =
        try {
            val key = store.getKey(alias, null) as? PrivateKey ?: return false
            Cipher.getInstance(RSA).init(Cipher.DECRYPT_MODE, key, OAEP)
            true
        } catch (e: KeyPermanentlyInvalidatedException) {
            false
        } catch (e: GeneralSecurityException) {
            // Une authentification manque, mais la clé est bonne.
            true
        }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val AES = "AES/GCM/NoPadding"
        const val RSA = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"

        /** OAEP SHA-256, MGF1 SHA-1 : ce que le Keystore accepte partout. */
        val OAEP = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)

        fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

        fun b64(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP)
    }
}
