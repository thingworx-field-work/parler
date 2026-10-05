---
name: not_bad_skill
title: Intentionally invalid repository skill
description: This file is intentionally invalid because the frontmatter name does not match the directory id.
skill_meta_version: 1
---

This skill is intentionally malformed for repository-scan testing.

The directory id is `bad_skill`, but the frontmatter declares `name: not_bad_skill`.
Parler should skip this entry and report a skill registry diagnostic instead of registering it.
