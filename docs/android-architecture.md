# Mosaic Android architecture

## Goal

Ship one Android application named **Mosaic** while keeping nutrition, photos, swimming, machine learning, settings, and synchronization independently maintainable.

The application should feel unified to the user without becoming a single tightly coupled code module.

## Target module map

```text
app
core:ui
core:model
core:database
core:settings
core:sync
feature:fit
feature:photos
feature:swim
feature:settings
ml:runtime
ml:faces
ml:objects
```

## Responsibilities

### `app`

- Android application entry point
- top-level navigation between Mosaic areas
- dependency assembly
- application theme and process-level lifecycle
- no nutrition, photos, or swimming business logic

### `core:ui`

- shared Compose components
- themes, typography, spacing, and accessibility conventions
- loading, error, empty, and permission states

### `core:model`

- small shared value types that genuinely cross feature boundaries
- identifiers, timestamps, sync state, and device metadata
- no feature-specific database entities

### `core:database`

- Room database construction and migrations
- registration of feature-owned entities and DAOs
- transaction boundaries shared by synchronization

Each feature owns its DAO contract and must not directly access another feature's DAO.

### `core:settings`

- server address and device settings
- selected theme
- future user-level preferences

### `core:sync`

- future sync queue and conflict metadata
- network-independent sync state machine
- WorkManager scheduling

### `feature:fit`

- meal capture and analysis
- meal confirmation and corrections
- nutrition history and trends
- future weight and general fitness tracking

### `feature:photos`

- MediaStore indexing
- screenshots and downloads classification
- archive and deletion review flows
- photo metadata, labels, people, and local search

### `feature:swim`

- planned workouts
- swim sessions and interval analysis
- future Wear OS integration

### `feature:settings`

- app-wide settings UI
- permissions and storage management
- synchronization status

### `ml:*`

- `ml:runtime`: model loading, image preprocessing, execution policy
- `ml:faces`: face detection, embeddings, and clustering contracts
- `ml:objects`: object classification and detection contracts

Feature modules consume ML interfaces rather than depending on a particular TensorFlow Lite model implementation.

## Data ownership

The app may use one Room database file, but tables and DAOs remain feature-owned.

Future records intended for synchronization should gradually adopt:

```text
id
createdAt
updatedAt
deletedAt
syncState
deviceId
version
```

This change must be introduced through explicit Room migrations rather than destructive database resets.

## Photo safety rules

- Access user photos through Android MediaStore and supported permission APIs.
- Never assume direct filesystem access to the whole device.
- AI classification may recommend archive or deletion, but destructive actions require a clear review flow.
- Background indexing should run in bounded WorkManager batches and respect battery constraints.

## Migration plan

### Stage 1 — identity and documentation

- app label becomes `Mosaic`
- Gradle root becomes `MosaicAndroid`
- repository is intended to become `mosaic-android`
- existing package, application ID, Room database, preferences, and behavior stay unchanged

### Stage 2 — extract shared foundations

Create `core:ui`, `core:model`, `core:database`, and `core:settings`. Move code without changing user-visible behavior.

### Stage 3 — extract Mosaic Fit

Move nutrition screens, persistence, and domain models into `feature:fit`. Keep the current experience working through the `app` navigation shell.

### Stage 4 — create Mosaic home and Photos placeholder

Introduce top-level Mosaic navigation and a minimal `feature:photos` destination before adding storage permissions or ML.

### Stage 5 — photo library foundation

Index MediaStore metadata locally, add archive candidates, and build safe review flows.

### Stage 6 — on-device ML

Add bounded object and face pipelines behind `ml:*` interfaces. Store derived metadata and embeddings locally.

### Stage 7 — synchronization

Add WorkManager-based synchronization with the home computer through stable record IDs, versions, tombstones, and conflict handling.

## Explicit non-goals for Stage 1

- no package rename
- no `applicationId` rename
- no Room schema change
- no preference-key change
- no API contract change
- no feature behavior change
