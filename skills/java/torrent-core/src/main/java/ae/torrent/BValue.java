package ae.torrent;

/** A bencode value: integer, byte string, list or dictionary. */
public sealed interface BValue permits BInt, BStr, BList, BDict {
}
