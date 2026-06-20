import com.github.badoualy.telegram.api.TelegramApiStorage;
import com.github.badoualy.telegram.mtproto.auth.AuthKey;
import com.github.badoualy.telegram.mtproto.model.DataCenter;
import com.github.badoualy.telegram.mtproto.model.MTSession;

/**
 * In-memory storage for the spike (no persistence across runs).
 * Implemented in Java to avoid Kotlin's strict override matching against the
 * library's platform-type signatures.
 */
public class MemoryStorage implements TelegramApiStorage {
    private AuthKey authKey;
    private DataCenter dc;
    private MTSession session;

    @Override public void saveAuthKey(AuthKey authKey) { this.authKey = authKey; }
    @Override public AuthKey loadAuthKey() { return authKey; }
    @Override public void deleteAuthKey() { authKey = null; }

    @Override public void saveDc(DataCenter dataCenter) { this.dc = dataCenter; }
    @Override public DataCenter loadDc() { return dc; }
    @Override public void deleteDc() { dc = null; }

    @Override public void saveSession(MTSession session) { this.session = session; }
    @Override public MTSession loadSession() { return session; }
}
