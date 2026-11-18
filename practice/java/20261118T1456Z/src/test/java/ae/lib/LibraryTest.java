package ae.lib;

/** Step 2: the same table against the refactored facade. */
class LibraryTest extends LibraryContract {
    @Override
    Api create() { return Api.refactored(); }
}
