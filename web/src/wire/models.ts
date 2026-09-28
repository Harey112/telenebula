export interface DexIdentity { name: string; ip: string }

export type DexDirection = "in" | "out";
export type DexMessageStatus = "pending" | "sent" | "delivered" | "offered" | "declined" | "cancelled" | "receiving" | "received";
export type DexMessageKind = "text" | "image" | "video" | "file" | "voice";
export type DexSendState = "queued" | "sending" | "waiting" | "failed";
export type DexRevealGate = "tap" | "ask" | "code" | "device";
export type DexPresence = "online" | "reachable" | "offline";

export interface DexAttachment {
  name: string;
  mime: string;
  size: number;
  width?: number;
  height?: number;
  durationMs?: number;
  hasFile: boolean;
}

export interface DexReplyPreview { id: string; name: string; snippet: string }

export interface DexMessage {
  id: string;
  peer: string;
  dir: DexDirection;
  body: string;
  ts: number;
  status: DexMessageStatus;
  kind: DexMessageKind;
  att?: DexAttachment;
  isEdited?: boolean;
  isDeleted?: boolean;
  reactions?: Record<string, string>;
  replyTo?: DexReplyPreview;
  seenAt?: number;
  isRead?: boolean;
  expiresAt?: number;
  isCovered?: boolean;
  isCancelledByMe?: boolean;
  send?: DexSendState;
  actionId?: string;
  transferPct?: number;
}

export interface DexContact {
  ip: string;
  label: string;
  name: string;
  contactLabel?: string;
  privacy?: DexContactPrivacy;
  notifications?: DexContactNotifications;
  nickname?: string;
  notes?: string;
  isBlocked?: boolean;
  isArchived?: boolean;
  isPinned?: boolean;
  muteUntil?: number;
  addedAt?: number;
  lastSeenAt?: number;
  revealGate?: DexRevealGate;
  disappearSeconds?: number;
}

export interface DexChat {
  peer: string;
  label: string;
  lastBody?: string;
  lastTs?: number;
  lastDir?: DexDirection;
  lastStatus?: DexMessageStatus;
  lastSend?: DexSendState;
  unread?: number;
  isPinned?: boolean;
  isArchived?: boolean;
  isBlocked?: boolean;
  isMuted?: boolean;
  isMarkedUnread?: boolean;
}

export interface DexChatView {
  peer: string;
  contact: DexContact;
  messages: DexMessage[];
  hasMore: boolean;
  freeBytes?: number;
}

export interface DexQueue { peer: string; queued: number; isReachable: boolean; isDraining: boolean }

export type DexCallPhase = "idle" | "incoming" | "contacting" | "ringing" | "connecting" | "active" | "ended";
export type DexSeat = "none" | "phone" | "dex";
export interface DexCallPeer { ip: string; name: string }

export interface DexCallState {
  phase?: DexCallPhase;
  callId?: string;
  peer?: DexCallPeer;
  video?: boolean;
  seat?: DexSeat;
  seatClientId?: string;
  startedAt?: number;
  remoteCamOn?: boolean;
  movingTo?: DexSeat;
  endedReason?: string;
}

export interface DexIceServer { urls: string[]; username?: string; credential?: string }
export interface DexIceCandidate { candidate: string; sdpMid?: string; sdpMLineIndex?: number }

export type DexThemeMode = "system" | "light" | "dark";
export type DexTextSize = "small" | "medium" | "large";
export type DexDensity = "comfortable" | "compact";
export type DexLogLevel = "info" | "debug";

export interface DexQuietHours {
  enabled?: boolean;
  fromHour?: number;
  fromMinute?: number;
  toHour?: number;
  toMinute?: number;
}

export interface DexMessageNotifications { showSender?: boolean; vibrate?: boolean; popup?: boolean; reactions?: boolean }
export interface DexCallNotifications { ring?: boolean; vibrate?: boolean; missedNotification?: boolean }
export interface DexInAppNotifications { vibrate?: boolean }
export interface DexNotifications {
  messages?: DexMessageNotifications;
  calls?: DexCallNotifications;
  inApp?: DexInAppNotifications;
  quietHours?: DexQuietHours;
}

export interface DexPresencePrefs { isShared?: boolean; pauseMinutes?: number; pausedUntil?: number }
export interface DexUpdatePrefs { isDailyCheckEnabled?: boolean; lastCheckedAt?: number; latestVersion?: string }

export interface DexProfile {
  sendReadReceipts?: boolean;
  sendTypingIndicators?: boolean;
  coverRevealGate?: DexRevealGate;
  themeMode?: DexThemeMode;
  colorTheme?: string;
  customAccent?: string;
  chatTextSize?: DexTextSize;
  messageDensity?: DexDensity;
  isEnterToSend?: boolean;
  notificationsEnabled?: boolean;
  notificationPreview?: boolean;
  notificationSound?: boolean;
}

export interface DexSettings {
  isScreenshotBlocked?: boolean;
  isBackgroundConnectionEnabled?: boolean;
  isStartOnBootEnabled?: boolean;
  notifications?: DexNotifications;
  presence?: DexPresencePrefs;
  updates?: DexUpdatePrefs;
  nebulaLogLevel?: DexLogLevel;
  isDeveloperMode?: boolean;
  autoCleanOrphans?: boolean;
  quickReactions?: string[];
  recentReactions?: string[];
  isAppLockEnabled?: boolean;
  appLockAfterSec?: number;
  dexUsername?: string;
  dexMaxClients?: number;
  dexPort?: number;
  dexProfile?: DexProfile;
}

export interface DexSettingsPatch {
  presence?: DexPresencePrefs;
  nebulaLogLevel?: DexLogLevel;
  dexProfile?: DexProfile;
}

export interface DexAccount {
  certName?: string;
  overlayIp?: string;
  certFingerprint?: string;
  certNotAfter?: string;
  certStatus?: string;
  networks?: string[];
  listenPort?: number;
  msgPort?: number;
  mtu?: number;
  lighthouseIp?: string;
  lighthouseUnderlay?: string;
  appVersion?: string;
  coreVersion?: string;
}

export interface DexPeerRow {
  ip: string;
  label: string;
  isConnected?: boolean;
  endpoint?: string;
  latencyMs?: number;
}

export interface DexNetwork {
  isTunnelOn?: boolean;
  tunnelUptimeMs?: number;
  engineUptimeMs?: number;
  connectedCount?: number;
  bytesSent?: number;
  bytesReceived?: number;
  pendingActions?: number;
  failedActions?: number;
  pendingHandshakes?: number;
  lighthouseStatus?: string;
  peers?: DexPeerRow[];
}

export interface DexStorage {
  dbBytes?: number;
  attachmentsBytes?: number;
  attachmentsCount?: number;
  orphanBytes?: number;
  orphanCount?: number;
  partialBytes?: number;
  partialCount?: number;
  messages?: number;
  contacts?: number;
  freeBytes?: number;
}

export interface DexDiagnostics {
  logTail?: string;
  callTrail?: string[];
  queuedCount?: number;
  queuedPeers?: number;
  failedCount?: number;
  lighthouseStatus?: string;
}

export interface DexUpdates {
  appVersion?: string;
  latestVersion?: string;
  isUpdateAvailable?: boolean;
  isDailyCheckEnabled?: boolean;
  lastCheckedAt?: number;
  lastError?: string;
}

export interface DexPeerStats {
  messagesSent?: number;
  messagesReceived?: number;
  mediaSent?: number;
  mediaReceived?: number;
  bytesSent?: number;
  bytesReceived?: number;
  firstMessageAt?: number;
  lastActivityAt?: number;
  pendingActions?: number;
  failedActions?: number;
  isConnected?: boolean;
}

export type DexCallOutcome = "answered" | "missed" | "declined" | "no-answer" | "unreachable" | "cancelled" | "failed";

export interface DexCallLog {
  id: string;
  peer: string;
  label: string;
  dir: DexDirection;
  isVideo: boolean;
  outcome: DexCallOutcome;
  startedAt: number;
  connectedAt?: number;
  endedAt: number;
}

export interface DexChatLink { messageId: string; url: string; ts: number }

export interface DexContactNotifications {
  useGlobal?: boolean;
  messages?: boolean;
  preview?: boolean;
  sound?: boolean;
  vibrate?: boolean;
  popup?: boolean;
  reactions?: boolean;
  calls?: boolean;
}

export interface DexContactPrivacy {
  sendReadReceipts?: boolean;
  sendTypingIndicators?: boolean;
  blockScreenshots?: boolean;
  revealGate?: DexRevealGate;
}

export interface DexContactFlags {
  isPinned?: boolean;
  isArchived?: boolean;
  isBlocked?: boolean;
  muteUntil?: number;
  isMarkedUnread?: boolean;
  disappearSeconds?: number;
}

export interface DexContactDetail {
  contact: DexContact;
  stats?: DexPeerStats;
  presence?: DexPresence;
  clientVersion?: string;
  peerCertName?: string;
  peerCertFingerprint?: string;
  endpoint?: string;
  connectionStatus?: string;
  queued?: number;
  failed?: number;
  privacy?: DexContactPrivacy;
  notifications?: DexContactNotifications;
  calls?: DexCallLog[];
}

export interface DexPingResult { peer: string; rttMs: number; error?: string }
export type DexNoticeLevel = "info" | "warning" | "error";
