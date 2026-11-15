// apps/*-module: a role module, that is a Groovy application-mode module plus the Java it calls.
//
// P1 gives it its dependency rules only, which are those of any Deephaven library.
// P2 adds the packaging of src/main/appmode/ into the jar and the validateAppModule task.

plugins {
    id("dhc.deephaven-library")
}
