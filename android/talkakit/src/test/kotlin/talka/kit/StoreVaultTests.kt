// La clé de l'appareil derrière sa garde (ici en mémoire ; le Keystore dans
// l'application) : tirée une fois, jamais remplacée quand la garde ne
// répond pas, enveloppée par le code de verrouillage, ouverte par la
// biométrie. La garde de l'iPhone (le trousseau) suit les mêmes règles.

package talka.kit

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoreVaultTests {
    private lateinit var guard: MemoryKeyGuard

    @BeforeTest
    fun setUp() {
        guard = MemoryKeyGuard()
        StoreVault.keyGuard = guard
    }

    @AfterTest
    fun tearDown() {
        StoreVault.keyGuard = null
    }

    @Test
    fun testWithoutAGuardThereIsNoKey() {
        StoreVault.keyGuard = null
        assertNull(StoreVault.key())
        assertFalse(StoreVault.setCode("1234", newKey()))
        assertFalse(StoreVault.isCodeLocked)
    }

    @Test
    fun testTheKeyIsDrawnOnceAndNeverReplacedWhenTheGuardIsSilent() {
        val key = assertNotNull(StoreVault.key())
        assertEquals(32, key.size)
        assertContentEquals(key, StoreVault.key())
        assertEquals(KeyGuard.Access.plain, guard.items["cle"]?.second)
        // La garde ne répond pas : rien, et surtout pas une autre clé.
        guard.failing = true
        assertNull(StoreVault.key())
        guard.failing = false
        assertContentEquals(key, StoreVault.key())
        // Tout effacé : une nouvelle.
        StoreVault.reset()
        assertTrue(guard.items.isEmpty())
        val fresh = assertNotNull(StoreVault.key())
        assertFalse(key.contentEquals(fresh))
    }

    @Test
    fun testTheCodeLocksTheKeyAndTheFaceOpensIt() {
        val key = assertNotNull(StoreVault.key())
        assertTrue(StoreVault.setCode("2580", key))
        assertTrue(StoreVault.isCodeLocked)
        assertNull(guard.items["cle"], "plus de copie en clair")
        assertNull(StoreVault.key())
        assertNull(StoreVault.key("0000"))
        assertContentEquals(key, StoreVault.key("2580"))
        // La copie gardée est le JSON de `CodeLock`, comme sur l'iPhone.
        val lock = assertNotNull(JSON.decode(StoreVault.CodeLock, String(guard.items["cle-code"]!!.first)))
        assertContentEquals(key, StoreVault.unwrap(lock, "2580"))
        // La biométrie, en plus du code.
        assertFalse(StoreVault.hasFaceUnlock)
        assertTrue(StoreVault.setFaceUnlock(true, key))
        assertTrue(StoreVault.hasFaceUnlock)
        assertEquals(KeyGuard.Access.biometry, guard.items["cle-visage"]?.second)
        assertContentEquals(key, StoreVault.keyByFace(Any()))
        // Le code retiré : la clé redevient lisible par Talka seule.
        assertTrue(StoreVault.setCode(null, key))
        assertFalse(StoreVault.isCodeLocked)
        assertFalse(StoreVault.hasFaceUnlock)
        assertNull(guard.items["cle-code"])
        assertNull(guard.items["cle-visage"])
        assertContentEquals(key, StoreVault.key())
        assertFalse(StoreVault.setFaceUnlock(true, key), "sans code, pas de copie pour la biométrie")
    }

    @Test
    fun testProtectionMovesTheKeyBehindTheUser() {
        val key = assertNotNull(StoreVault.key())
        assertTrue(StoreVault.setProtected(true, key))
        assertTrue(StoreVault.isProtected)
        assertNull(guard.items["cle"])
        assertEquals(KeyGuard.Access.presence, guard.items["cle-protegee"]?.second)
        // Sans authentification, rien ; avec, la clé.
        assertNull(StoreVault.key())
        assertContentEquals(key, StoreVault.key(Any()))
        assertTrue(StoreVault.setProtected(true, key))
        assertTrue(StoreVault.setProtected(false, key))
        assertFalse(StoreVault.isProtected)
        assertNull(guard.items["cle-protegee"])
        assertContentEquals(key, StoreVault.key())
    }
}
