import type {
  DexAccount, DexCallLog, DexCallState, DexChat, DexChatLink, DexChatView,
  DexContact, DexContactDetail, DexContactFlags, DexContactNotifications,
  DexContactPrivacy, DexDiagnostics, DexIceCandidate, DexIceServer, DexIdentity,
  DexMessage, DexNetwork, DexNoticeLevel, DexPingResult, DexPresence, DexQueue,
  DexSettings, DexSettingsPatch, DexStorage, DexUpdates,
} from "./models";

export type ClientFrame =
  | { t: "ping" }
  | { t: "open_chat"; peer: string }
  | { t: "close_chat"; peer: string }
  | { t: "load_more"; peer: string; beforeTs: number; beforeId: string }
  | { t: "send_text"; peer: string; body: string; replyTo?: string; covered?: boolean; requestId?: string }
  | { t: "typing"; peer: string; isTyping: boolean }
  | { t: "mark_read"; peer: string }
  | { t: "react"; messageId: string; emoji: string }
  | { t: "edit"; messageId: string; body: string }
  | { t: "delete"; messageId: string; forEveryone: boolean }
  | { t: "retry_action"; actionId: string }
  | { t: "cancel_action"; actionId: string }
  | { t: "accept_offer"; messageId: string }
  | { t: "decline_offer"; messageId: string }
  | { t: "cancel_transfer"; messageId: string }
  | { t: "call_start"; peer: string; video: boolean }
  | { t: "call_accept"; callId: string }
  | { t: "call_reject"; callId: string }
  | { t: "call_end"; callId: string }
  | { t: "call_sdp"; callId: string; sdp: string; sdpType: string }
  | { t: "call_ice"; callId: string; candidate?: DexIceCandidate }
  | { t: "call_connected"; callId: string }
  | { t: "call_failed"; callId: string; reason: string }
  | { t: "call_cam"; callId: string; isOn: boolean }
  | { t: "call_move_to_phone"; callId: string }
  | { t: "watch"; sections: string[] }
  | { t: "set_settings"; patch: DexSettingsPatch }
  | { t: "set_quick_reaction"; slot: number; emoji: string }
  | { t: "request_contact_detail"; peer: string }
  | { t: "contact_save"; peer: string; nickname: string; notes: string }
  | { t: "contact_add"; peer: string; nickname?: string; notes?: string }
  | { t: "contact_delete"; peer: string }
  | { t: "contact_flags"; peer: string; flags: DexContactFlags }
  | { t: "contact_privacy"; peer: string; privacy: DexContactPrivacy }
  | { t: "contact_notifications"; peer: string; prefs?: DexContactNotifications }
  | { t: "contact_change_ip"; peer: string; newIp: string }
  | { t: "clear_history"; peer: string }
  | { t: "clear_all_history" }
  | { t: "clear_orphans" }
  | { t: "request_call_logs"; peer?: string; limit?: number }
  | { t: "delete_call_logs"; ids: string[] }
  | { t: "request_chat_media"; peer: string }
  | { t: "request_chat_links"; peer: string }
  | { t: "ping_peer"; peer: string }
  | { t: "retry_failed"; peer?: string }
  | { t: "drain"; peer: string }
  | { t: "check_updates" }
  | { t: "set_tunnel"; isOn: boolean }
  | { t: "forward"; messageId: string; peer: string }
  | { t: "search"; peer: string; text: string };

export type ServerFrame =
  | { t: "pong" }
  | { t: "hello"; me: DexIdentity; clientId: string; freeBytes: number }
  | { t: "chats"; items: DexChat[] }
  | { t: "contacts"; items: DexContact[] }
  | { t: "chat"; view: DexChatView }
  | { t: "chat_more"; peer: string; messages: DexMessage[]; hasMore: boolean }
  | { t: "presence"; items: Record<string, DexPresence> }
  | { t: "typing"; peers: string[] }
  | { t: "queues"; items: DexQueue[] }
  | { t: "tunnel"; isOn: boolean }
  | { t: "notice"; level: DexNoticeLevel; message: string }
  | { t: "error"; message: string; ref?: string; requestId?: string }
  | { t: "call_state"; state: DexCallState }
  | { t: "call_media"; callId: string; role: string; video: boolean; iceServers: DexIceServer[]; remoteSdp?: string; remoteSdpType?: string; isRestart?: boolean }
  | { t: "call_sdp"; callId: string; sdp: string; sdpType: string }
  | { t: "call_ice"; callId: string; candidate?: DexIceCandidate }
  | { t: "call_release"; callId: string; reason: string }
  | { t: "settings"; settings: DexSettings }
  | { t: "account"; account: DexAccount }
  | { t: "network"; network: DexNetwork }
  | { t: "storage"; storage: DexStorage }
  | { t: "diagnostics"; diagnostics: DexDiagnostics }
  | { t: "updates"; updates: DexUpdates }
  | { t: "call_logs"; items: DexCallLog[] }
  | { t: "contact_detail"; detail: DexContactDetail }
  | { t: "chat_media"; peer: string; items: DexMessage[] }
  | { t: "chat_links"; peer: string; items: DexChatLink[] }
  | { t: "ping_result"; result: DexPingResult }
  | { t: "search_results"; peer: string; items: DexMessage[] }
  | { t: "done"; what: string; message?: string; requestId?: string };
