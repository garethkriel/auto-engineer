package ae.lib;

/** Step 1: the table must be green against the untouched legacy code. */
class LegacyLibraryTest extends LibraryContract {
    @Override
    Api create() { return Api.legacy(); }
}
