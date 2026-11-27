// Le modèle de l'application (`AppModel`), sans écran : créer son compte,
// le verrou (code, biométrie), la protection de la base, l'effacement, les
// liens d'invitation, la sauvegarde. Contre un faux relais local.

package talka.app

import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import talka.kit.Engine
import talka.kit.Identity
import talka.kit.Relay
import talka.kit.StoreVault
import talka.kit.TalkaCrypto
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppModelTest : AppTestBase() {
    @Before
    fun setUp() = install()

    @After
    fun tearDown() = uninstall()

    @Test
    fun createAccountStartsTheEngineAndSurvivesARestart() {
        val m = model()
        assertNull(m.engine, "pas de compte : l'accueil")
        assertNull(m.loading, "l'écran de chargement s'en va")
        m.createAccount("Léa", "")
        idle()
        val e = assertNotNull(m.engine)
        assertEquals("Léa", e.profile.name)
        assertEquals(Relay.State.online, e.netState, "le faux relais accepte la présentation")
        assertEquals("Pixel de test", Engine.deviceName)
        val code = e.me.code
        quit(m)
        // Relancé : le compte est là, la base est chiffrée par la clé de la garde.
        assertNotNull(guard.items["cle"])
        val again = model()
        assertEquals(code, again.engine?.me?.code)
        assertEquals("Léa", again.engine?.profile?.name)
        assertFalse(again.locked)
    }

    @Test
    fun pinEncryptsTheBaseAndOpensIt() {
        val m = model()
        m.createAccount("Léa", "")
        run(m) { m.setPin("482916") }
        assertTrue(m.hasPin)
        assertTrue(m.codeEncrypts)
        assertTrue(StoreVault.isCodeLocked)
        assertNull(guard.items["cle"], "plus de copie de la clé en clair")
        quit(m)

        val cold = model()
        assertTrue(cold.codeLocked)
        assertNull(cold.engine)
        assertFalse(run(cold) { cold.unlock("111111") })
        assertTrue(cold.codeLocked)
        assertTrue(run(cold) { cold.unlock("482916") })
        idle()
        assertFalse(cold.codeLocked)
        assertFalse(cold.locked)
        assertEquals("Léa", cold.engine?.profile?.name)

        // Retiré : la base s'ouvre de nouveau sans code.
        run(cold) { cold.setPin(null) }
        assertFalse(cold.hasPin)
        assertFalse(StoreVault.isCodeLocked)
        quit(cold)
        val open = model()
        assertFalse(open.codeLocked)
        assertFalse(open.locked)
        assertEquals("Léa", open.engine?.profile?.name)
    }

    @Test
    fun locksWhenLeavingButNotForAPhotoOrAFile() {
        val m = model()
        m.createAccount("Léa", "")
        run(m) { m.setPin("482916") }
        // Quitter Talka (verrouillage « dès que je quitte Talka ») : verrouillé au retour.
        m.scene(AppModel.Phase.inactive)
        m.scene(AppModel.Phase.background)
        assertFalse(m.active)
        m.scene(AppModel.Phase.active)
        assertTrue(m.locked)
        assertFalse(run(m) { m.checkPin("000000") })
        assertTrue(run(m) { m.checkPin("482916") })
        m.locked = false
        // Choisir une photo ou un fichier n'est pas quitter Talka.
        m.leavingOnPurpose()
        m.scene(AppModel.Phase.inactive)
        m.scene(AppModel.Phase.background)
        m.scene(AppModel.Phase.active)
        assertFalse(m.locked)
        // Mais la fois suivante, si.
        m.scene(AppModel.Phase.background)
        m.scene(AppModel.Phase.active)
        assertTrue(m.locked)
    }

    @Test
    fun biometryOpensTheCodeLockedBase() {
        val m = model()
        m.createAccount("Léa", "")
        run(m) { m.setPin("482916") }
        m.faceID = true
        assertTrue(StoreVault.hasFaceUnlock)
        assertEquals(talka.kit.KeyGuard.Access.biometry, guard.items["cle-visage"]?.first)
        quit(m)

        val cold = model()
        assertTrue(cold.codeLocked)
        biometrics.answer = false
        run(cold) { cold.unlockColdWithFace() }
        assertTrue(cold.codeLocked, "refusée : rien ne s'ouvre")
        biometrics.answer = true
        run(cold) { cold.unlockColdWithFace() }
        idle()
        assertFalse(cold.codeLocked)
        assertEquals("Léa", cold.engine?.profile?.name)
        // Talka ouvert : la biométrie lève aussi le verrou.
        cold.locked = true
        run(cold) { cold.unlockWithBiometry() }
        assertFalse(cold.locked)
    }

    @Test
    fun protectedVaultOpensOnlyWithBiometry() {
        val m = model()
        m.createAccount("Léa", "")
        run(m) { m.protectVault(true) }
        assertTrue(m.vaultProtected)
        assertEquals("Vos messages ne s'ouvrent qu'avec Empreinte digitale 🔐", m.toast?.text)
        assertEquals(talka.kit.KeyGuard.Access.presence, guard.items["cle-protegee"]?.first)
        quit(m)

        val locked = model()
        assertTrue(locked.vaultLocked)
        assertNull(locked.engine)
        biometrics.answer = false
        run(locked) { locked.unlockVault() }
        assertTrue(locked.vaultLocked)
        biometrics.answer = true
        run(locked) { locked.unlockVault() }
        assertFalse(locked.vaultLocked)
        assertEquals("Léa", locked.engine?.profile?.name)
        // Retirer la protection demande aussi la biométrie.
        run(locked) { locked.protectVault(false) }
        assertFalse(StoreVault.isProtected)
        assertEquals("Protection retirée", locked.toast?.text)
    }

    @Test
    fun wipeForgetsEverything() {
        val m = model()
        m.createAccount("Léa", "")
        run(m) { m.setPin("482916") }
        m.faceID = true
        m.path = listOf("K7Q2MX9PRT")
        m.wipe()
        idle()
        assertNull(m.engine)
        assertFalse(m.hasPin)
        assertFalse(m.faceID)
        assertTrue(m.path.isEmpty())
        assertFalse(StoreVault.isCodeLocked)
        assertFalse(Engine.hasAccount(m.store))
        quit(m)
        val again = model()
        assertNull(again.engine, "l'accueil")
        assertFalse(again.codeLocked)
    }

    @Test
    fun inviteLinksAndAddingAContact() {
        val m = model()
        m.createAccount("Léa", "")
        idle()
        val e = assertNotNull(m.engine)
        val friend = Identity.create()
        relay.known[friend.code] = friend
        // Le lien d'invitation (avec l'empreinte de ses clés) : la feuille « Ajouter un contact ».
        val fp = talka.kit.TalkaCode.fingerprint(friend.sign, friend.dh)
        m.open("https://relais.test/c/$fp/${friend.code}")
        assertEquals(friend.code, m.invite)
        assertEquals(AppModel.Tab.chats, m.tab)
        // Ajouté (comme le fait la feuille) : ses clés arrivent du relais, vérifiées.
        val reachable = run(m) { e.addContact(friend.code, "Max") }
        assertTrue(reachable)
        assertEquals(friend.sign, e.contact(friend.code)?.sign)
        // Un contact déjà là : le lien ouvre sa discussion.
        m.invite = null
        m.open("talka://add/${friend.code}")
        assertNull(m.invite)
        assertEquals(listOf(friend.code), m.path)
        assertNotNull(e.chat(friend.code))
        // Un code inconnu du relais : ajouté quand même, joignable plus tard.
        val stranger = Identity.create().code
        assertFalse(run(m) { e.addContact(stranger, "") })
        assertEquals(true, e.contact(stranger)?.saved)
        // Notre propre code.
        assertFailsWith<Engine.AddError.Yourself> { run(m) { e.addContact(e.me.code, "") } }
        // Notre lien d'invitation porte l'empreinte de nos clés.
        assertEquals("http://relais.test/c/${talka.kit.TalkaCode.fingerprint(e.me.sign, e.me.dh)}/${e.me.code}", m.inviteLink(e.me.code))
    }

    @Test
    fun backupRestoresElsewhereAndAWrongPasswordChangesNothing() {
        val m = model()
        m.createAccount("Léa", "")
        idle()
        val e = assertNotNull(m.engine)
        run(m) { e.addContact(Identity.create().code, "Max") }
        val code = e.me.code
        val backup = assertNotNull(e.exportBackup("un bon mot de passe"))
        assertTrue(m.backupFilename.matches(Regex("talka-sauvegarde-\\d{4}-\\d{2}-\\d{2}\\.talka")))

        // Mauvais mot de passe : une erreur, et Talka continue comme avant.
        assertFailsWith<TalkaCrypto.BackupError.WrongPassword> { run(m) { m.restore(backup, "pas le bon") } }
        assertEquals(code, m.engine?.me?.code)
        m.wipe()
        idle()
        assertNull(m.engine)
        run(m) { m.restore(backup, "un bon mot de passe") }
        idle()
        assertEquals(code, m.engine?.me?.code)
        assertEquals("Max", m.engine?.contacts?.values?.single()?.name)
    }

    @Test
    fun incomingMessagesShowAToastOutsideTheirChat() {
        val m = model()
        m.createAccount("Léa", "")
        idle()
        val e = assertNotNull(m.engine)
        val chat = e.ensureDirectChat("K7Q2MX9PRT")
        val msg = talka.kit.Message(chat.id, "m1", "K7Q2MX9PRT", 1.0, talka.kit.Content(text = "Coucou"))
        m.incoming(chat, msg, false)
        assertEquals("K7Q2M-X9PRT : Coucou", m.toast?.text)
        m.toast = null
        m.path = listOf(chat.id)
        m.incoming(chat, msg, false)
        assertNull(m.toast, "la discussion est à l'écran")
    }
}
