# ADR 0004: Rebuild rather than fork

**Status:** Draft

## Context

Simple has no license. There is no `LICENSE` file, no SPDX header in any source file, and no
mention of terms in the README or the Gradle files — the only matches for "license" anywhere in
the tree are an unrelated Compose lint rule and a `packaging { excludes }` line about
dependency metadata. With no licence granted, the default applies: all rights reserved.

GitHub's terms let us view the repository and fork it on GitHub. They do not grant the right to
modify the code, prepare derivative works, or distribute them. Everything we have planned is
exactly that, and we intend to install builds on someone else's phone.

The project also looks finished. The last commit is 2025-01-12, twenty months ago. It has one
issue, #1, open since June 2025 with no maintainer reply in fifteen months. That issue is our
crash, reported independently by someone else:

> When I create a 3rd or more pages then try to select a page number in the widget creation or
> edit, the app immediately crashes when opening the menu.

That is `substring(0, 20)` on a page summary shorter than twenty characters. Having more pages
does not cause it; it just improves the odds that one of them is short. So the bug we diagnosed
has been sitting in the tracker, correctly described, for over a year, and nobody is reading it.
There is no realistic route to landing a fix upstream and no quick route to getting a licence
added.

Against that, it is worth being honest about how much of Simple we would actually keep. Our own
ADRs already replace the storage format, the data model, the identity scheme, the configuration
screen's validation and the theming, and add groups, ordering and custom names. What is left is
querying launcher activities, drawing text in a Glance widget, and launching an app by package
name. A rewrite is not the expensive option here — it is most of what we had already decided to
do, minus a migration.

## Decision

Write a new app rather than continue on the fork. New repository, new application id, no code,
assets or storage format carried across.

Keep contributing the launcher fix to Lawnchair, which is Apache-2.0 and actively maintained.
That work is unaffected by any of this and is now the only upstream contribution we have.

Separately, and as a courtesy rather than a dependency, file an issue on Simple asking the
maintainer to add a licence. If it lands, everyone who wants to fork the project benefits. Do
not wait on it and do not plan around it.

## Alternatives considered

**Fork anyway.** The fork button exists, and for one person patching one app for their own
phone the practical risk rounds to nothing. Rejected because we are not doing that: we intend to
keep developing it and to install builds on someone else's device, which means preparing and
distributing derivative works. The defect is also permanent — without the maintainer it can
never be cured, so anything built on it can never be shared.

**Ask for a licence and wait.** Fifteen months of silence on the only issue in the tracker is
the answer to how long that wait is.

**Contribute the fixes upstream instead.** The outcome we would have preferred, and the reason
the fixes were written as small, targeted patches. Unavailable: no licence to contribute under,
and nobody to merge them.

**Keep a private patch set and never distribute.** Legitimate for her phone alone, and the
cheapest thing that works today. Rejected as a destination rather than a stopgap: the features
we are designing amount to a different app, and maintaining a private fork of an abandoned
codebase is more work than maintaining our own.

## Consequences

Her data does not come across. One app cannot read another's `SharedPreferences`, so there is no
migration to write — she re-enters her pages once. She can keep both installed and only remove
Simple when the replacement is set up, which makes the retyping a copy job rather than a
recall job.

The hardest part of ADR 0003 disappears. There is no legacy delimited format to migrate, no
second preferences key, and no rollback window to reason about. Groups, ordering and custom
names get designed into the model instead of bolted onto one that cannot hold them.

ADR 0002 splits. Its Simple half is moot — we will not write the crash in the first place. Its
Lawnchair half is unchanged and is still worth landing.

**The unexplained bug follows us.** We never established why the app-selection screen renders
black on black on a stock Pixel. A rewrite does not fix an unknown cause; if it is something
about Compose, dynamic colour and that device, we can reproduce it perfectly in new code. The
observations listed in ADR 0001 matter more now than they did, because we are choosing the
theming approach from scratch rather than patching one.

We have read the original closely, so some discipline is warranted even though the expression
worth protecting in a six-hundred-line utility is thin. Concretely: a different application id,
no copied assets, no reuse of the `label|package++…###` format or the class layout, and no
copying of source. The idea of a text-only launcher widget is not protectable and is not what we
are avoiding.

The fixes written on the fork are discarded as code. They stay on the branch as the record of
what each bug was, and they double as the regression list for the new app.

## Open questions

**Name and application id.** Both must differ from `xyz.michaelzhao.simple`. The name is not a
legal problem but a different one avoids confusion.

**Where the repository lives and who creates it.** This session cannot create or push to a
repository outside the two already attached.

**Minimum SDK.** Simple requires Android 14, which is higher than anything it does warrants.
Glance needs 23 and dynamic colour needs 31. Lowering it costs a fallback path for the colour
scheme and widens the device range considerably.

**The second half of issue #1.** The same reporter says Chrome Remote Desktop and Kindle always
crash when launched from the widget. We have no diagnosis for that and it may not even be
Simple's fault, but both belong on the manual test list for the new app.
