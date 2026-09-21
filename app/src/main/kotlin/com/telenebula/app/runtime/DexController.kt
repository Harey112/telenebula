package com.telenebula.app.runtime

import com.telenebula.app.notices.NoticeCenter
import com.telenebula.app.platform.LanAddress
import com.telenebula.app.platform.LanAddresses
import com.telenebula.app.platform.PrefsRepository
import com.telenebula.core.model.DexPrefs
import com.telenebula.core.model.Profile
import com.telenebula.dex.DexAssets
import com.telenebula.dex.DexClient
import com.telenebula.dex.DexConfig
import com.telenebula.dex.DexException
import com.telenebula.dex.DexServer
import com.telenebula.dex.DexServerState
import com.telenebula.dex.Limits
import com.telenebula.dex.PasswordHash
import com.telenebula.dex.auth.Passwords
import com.telenebula.dex.http.Cidr
import com.telenebula.dex.tls.DexTls
import com.telenebula.dex.turn.TurnServer
import com.telenebula.dex.turn.TurnState
import java.net.InetAddress
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the Dex page and the Me row show. */
data class DexStatus(
    val isEnabled: Boolean = false,
    val isRunning: Boolean = false,
    val isStarting: Boolean = false,
    val failure: String? = null,
    val port: Int = 0,
    val addresses: List<LanAddress> = emptyList(),
    val fingerprint: String = "",
    val clients: List<DexClient> = emptyList(),
) {
    val urls: List<Pair<String, String>> get() = addresses.map { it.label to "https://${it.host}:$port" }
}

/**
 * Runs the Dex servers whenever the setting is on and the phone is set up, and stops them the
 * moment either changes. The certificate, the relay and the socket server are created once here.
 */
class DexController(
    private val scope: CoroutineScope,
    private val prefs: PrefsRepository,
    profile: StateFlow<Profile?>,
    private val backend: DexBackendAdapter,
    private val bridge: DexCallBridge,
    private val assets: DexAssets,
    private val notices: NoticeCenter,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val tls = DexTls()

    private val overlayAddress: InetAddress? get() = backend.me.value?.ip?.let(::parseAddress)

    val turn = TurnServer(
        scope = scope,
        configuredPort = prefs.prefs.value.dex.turnPort,
        relayAddress = { overlayAddress },
        isOverlayAddress = ::isOverlay,
        io = io,
        onFault = notices::addWarning,
    )

    val server = DexServer(
        backend = backend,
        assets = assets,
        socketFactory = { tls.serverSocketFactory() },
        turn = turn,
        scope = scope,
        io = io,
    )

    private val addresses = MutableStateFlow<List<LanAddress>>(emptyList())
    private val fingerprint = MutableStateFlow("")

    val status: StateFlow<DexStatus> = combine(prefs.prefs.map { it.dex }.distinctUntilChanged(), server.state, server.clients, addresses, fingerprint) { dex, state, clients, addrs, fp ->
        DexStatus(
            isEnabled = dex.isEnabled,
            isRunning = state is DexServerState.Running,
            isStarting = state is DexServerState.Starting,
            failure = (state as? DexServerState.Failed)?.message ?: (turn.state.value as? TurnState.Failed)?.message,
            port = (state as? DexServerState.Running)?.port ?: dex.port,
            addresses = addrs,
            fingerprint = fp,
            clients = clients,
        )
    }.stateIn(scope, SharingStarted.Eagerly, DexStatus(isEnabled = prefs.prefs.value.dex.isEnabled, port = prefs.prefs.value.dex.port))

    private var applied: Applied? = null

    private class Applied(val config: DexConfig, val turnPort: Int)

    init {
        scope.launch {
            combine(prefs.prefs.map { it.dex }.distinctUntilChanged(), profile) { dex, p -> dex to p }.collect { (dex, p) -> apply(dex, p) }
        }
        scope.launch { server.clients.collect(bridge::setClients) }
    }

    /** Re-reads the phone's addresses; cheap, so the Dex page calls it whenever it is shown. */
    fun refreshAddresses() {
        scope.launch { addresses.value = withContext(io) { LanAddresses.list() } }
    }

    /** Hashes and stores a new password; the server picks the change up through prefs. */
    fun setPassword(password: String) {
        val hash = Passwords.hash(password)
        prefs.update { it.copy(dex = it.dex.copy(passwordAlgorithm = hash.algorithm, passwordIterations = hash.iterations, passwordSalt = hash.saltB64, passwordHash = hash.hashB64)) }
    }

    fun setUsername(username: String) = prefs.update { it.copy(dex = it.dex.copy(username = username.trim())) }

    fun setMaxClients(count: Int) = prefs.update { it.copy(dex = it.dex.copy(maxClients = count.coerceIn(1, Limits.MAX_CLIENTS))) }

    fun setEnabled(isEnabled: Boolean) = prefs.update { it.copy(dex = it.dex.copy(isEnabled = isEnabled)) }

    private fun apply(dex: DexPrefs, profile: Profile?) {
        val config = configOf(dex)
        if (!dex.isEnabled || profile == null || config == null) {
            stopAll()
            return
        }
        val current = applied
        if (current != null && current.turnPort == dex.turnPort && current.config.port == config.port) {
            if (current.config != config) {
                server.update(config)
                applied = Applied(config, dex.turnPort)
            }
            return
        }
        stopAll()
        fingerprint.value = try {
            tls.fingerprintSha256
        } catch (e: CancellationException) {
            throw e
        } catch (e: DexException) {
            notices.addError("Dex could not create its certificate: ${e.message}")
            return
        } catch (e: Exception) {
            notices.addError("Dex could not create its certificate: ${e.message ?: e.javaClass.simpleName}")
            return
        }
        turn.start()
        (turn.state.value as? TurnState.Failed)?.let { notices.addWarning(it.message) }
        server.start(config)
        applied = Applied(config, dex.turnPort)
        refreshAddresses()
    }

    private fun stopAll() {
        if (applied == null) return
        applied = null
        server.stop()
        turn.stop()
    }

    private fun configOf(dex: DexPrefs): DexConfig? {
        if (!dex.hasCredentials) return null
        val hash = PasswordHash(dex.passwordAlgorithm, dex.passwordIterations, dex.passwordSalt, dex.passwordHash)
        return DexConfig(port = dex.port, username = dex.username, password = hash, maxClients = dex.maxClients.coerceIn(1, Limits.MAX_CLIENTS))
    }

    private fun isOverlay(address: InetAddress): Boolean {
        val networks = backend.overlayNetworks.value.mapNotNull(Cidr::parse)
        if (Cidr.anyContains(networks, address)) return true
        val own = overlayAddress
        return own != null && own == address
    }

    private fun parseAddress(text: String): InetAddress? = try {
        if (text.isBlank() || text.any { it.isLetter() && it !in "abcdefABCDEF" }) null else InetAddress.getByName(text.substringBefore('/'))
    } catch (e: UnknownHostException) {
        null
    }
}

