# 🧭 User Guide

How each role uses UrbanPulse: where to sign in, what each screen shows and what to do in it. It
only describes screens and actions that are built; one that is not says `Planned: #N`, and what it
will do is in *Screens per Role* of the [requirements](requirements.md). How to start the apps is in
the [development guide](development.md) and the [demo script](demo.md).

| Status | Meaning |
| --- | --- |
| Built | Every screen of the role works as described here |
| Partial | Some screens or actions work; the rest say `Planned: #N` |
| Planned | The role can sign in, but its screens are not built |

## 📋 Summary

| Role | App | Status |
| --- | --- | --- |
| [Citizen](#-citizen-mobile-app) | Mobile | Built |
| [Municipal operator](#-municipal-operator-web) | Web | Partial |
| [Technician](#-technician-web) | Web | Partial |
| [Administrator](#-administrator-web) | Web | Partial |
| [Analyst](#-analyst-web) | Web | Built |

## 🚪 Before You Start

### Which app

Citizens use the **mobile app**; municipal staff (operators, technicians, administrators and
analysts) use the **website**. Each app only lets in its own people:

- A citizen who signs in on the website sees *Use the UrbanPulse mobile app*.
- A staff account that signs in on the phone sees *Use the UrbanPulse website* and a *Sign out*
  button.

The API checks every permission again, so this is a convenience, not the protection (see the
[permission matrix](security.md#permission-matrix)).

### Getting an account

| Who | How |
| --- | --- |
| Citizen | *Create an account* on the sign-in screen of the mobile app (see [Sign up](#sign-up)) |
| Staff | An administrator creates the account with its role (Planned: #40) |
| First administrator of a new deployment | Created once by the people who run the server ([deployment guide](deployment.md#-create-the-first-administrator)) |
| Trying the apps | The sample accounts of the [demo script](demo.md#the-story) (one per role) or of *Development Dataset* in the [development guide](development.md). A local build can show one button per demo account on the sign-in screen ([Quick Sign-In](demo.md#quick-sign-in)) |

## 🔑 Signing In and Out

Both apps ask for the email and the password. After signing in, each role lands on its own screen:

| Role | Lands on |
| --- | --- |
| Citizen (mobile) | *New*, the report form |
| Municipal operator | *Inbox* |
| Technician | *Work queue* |
| Administrator | *Users* |
| Analyst | *Reports* |

What the sign-in messages mean:

| Message | What to do |
| --- | --- |
| *The email or the password is not correct.* | Check both; the message does not say which one is wrong, on purpose |
| *Too many attempts. Wait a few minutes and try again.* | Too many failed attempts with one email; wait and try again ([rate limits](security.md#rate-limits)) |
| *This account is disabled.* | Ask an administrator (staff) or the city council (citizens) to enable it |
| *UrbanPulse cannot be reached. Check your connection and try again.* | The device has no connection, or the server is down |
| *Your session has expired. Sign in again.* | The session ended; sign in again, nothing you saved is lost |

**First sign-in of a staff account:** an account an administrator creates must choose its own
password before it can sign in, so the one the administrator typed stops working. After the email
and that password, the website shows *Choose your password*: type the new password twice (at least
8 characters, different from the one you were given) and select *Change the password*. You go back
to the sign-in form; sign in with the new password. *Back to sign in* leaves the password as it was,
and the next sign-in asks again. Changing the password also ends your sessions on other devices, within about 15 minutes.

**How long you stay signed in:**

- **Website:** while the tab is open. The session is kept in memory only, so **reloading the page,
  opening a link in a new tab or closing the browser signs you out**. This is on purpose: no other
  script on the page can read a session that is not stored ([security guide](security.md)).
- **Mobile app:** on that phone until you sign out from *Profile*; the session is kept in the
  phone's secure storage.

*Sign out* (in the top bar of the website, in *Profile* on the phone) ends the session on that
device only; your other devices stay signed in.

## 📱 Citizen (Mobile App)

Three tabs: *New*, *My reports* and *Profile*.

### Sign up

1. On the sign-in screen, tap *Create an account*.
2. Enter your full name, your email and a password, and tap *Create account*. The form says what
   a valid password needs.
3. The app says *Your account is ready. Sign in to continue.* and takes you back to sign in.

If the email already has an account, the app says so: sign in instead.

### Report an incident

1. Open the *New* tab. The first time, the phone asks whether UrbanPulse may use your location:
   allow it. Without it you cannot report; the screen shows *Location is needed* and an
   *Open settings* button to allow it later.
2. Check the accuracy under the form (for example *Accuracy: 12 m*). Outdoors it is usually a few
   metres; indoors it can be much worse. Tap *Refresh* to read the position again before sending.
3. Write a **title** (a short summary) and a **description** (what happens and where). Both are
   required.
4. Choose a **category** if one fits. It is optional: staff classify the report if you leave it
   empty. If the categories cannot be loaded, you can still report without one.
5. Tap *Submit*. The app confirms that the incident was reported and offers *Add photos* or
   *My reports*.

The report is saved as soon as you submit it. Photos are optional, and a photo that cannot be
uploaded never loses the report.

### Add photos to a report

You can add photos while the report is waiting for review: right after sending it (*Add photos*
in the confirmation), or later with *Add photos* in its detail. Once the city council reviews the
report, the button disappears.

1. Tap *Take a photo* or *Choose from gallery*. The first time you take a photo, the phone asks
   whether UrbanPulse may use the camera. If you refuse, the screen offers *Open settings*; the
   gallery still works.
2. Check the photo and tap *Upload photo*. The bar shows how much has been sent. The app makes the
   photo smaller before sending it, so it uploads faster.
3. Uploaded photos appear under *Uploaded photos*. Repeat for more photos, up to the limit of the
   report.

If the connection fails, the app tries again on its own a couple of times. If it still cannot
upload the photo, it tells you that your report is saved without it, and *Try again* sends the same
photo again; it is never stored twice. A photo that is too large, is not a JPEG, PNG or WebP image,
or goes over the limit of the report is refused with its reason.

The report is accepted even when the address, neighbourhood or district cannot be worked out at
that moment: they show as *Not available* until they are known.

- Planned: choose or correct the position on a map (#60).

### Follow your reports

*My reports* lists your incidents, newest first, in two groups:

- **Open:** still being handled.
- **Closed:** finished, either closed or rejected.

Scroll down to load more. Tap a report to see:

- **Details:** its category (*Not set yet* when nobody has classified it), when it was reported,
  when it was last updated and, once closed, when it was closed.
- **Location:** address, neighbourhood, district, coordinates and accuracy; *Not available* when
  a value is not known.
- **History:** a timeline of each change of status, oldest first, with its date and time. Your
  report shows as yours (*You*) and every other change as the *City council*'s. A rejection shows
  its **reason**. When the city needs more details, the entry shows a *Message from the city
  council*.

You only see what the city shares with you: internal notes of the staff, priorities and who made
each change are not shown to citizens.

### Profile

Your name and email, and *Sign out*.

## 🗂️ Municipal Operator (Web)

The menu at the top shows *Inbox*. Planned: *Map* (#53).

### Inbox

The incidents, newest first, a page at a time, with the date it was reported, title, category,
status and priority. A category or priority that is not set shows *Not classified* or *Not set*.

The menu and the sign-in open it on the reports waiting for a review: the status *Reported* is
ticked. *Clear filters* lists every incident.

- **Filters:** status, category, priority, department and the days it was reported (*Reported
  from* and *to*, both included, in Málaga time). They combine with each other, and each change
  applies at once and goes back to the first page. The department is the one the incident is
  assigned to now. Several statuses can be ticked at once: the list shows the incidents in any of
  them, and with none ticked it shows every status. The district filter is planned (#59).
- *Previous* and *Next* change the page. The filters and the page are part of the address
  (`/inbox?status=REPORTED&page=2`), so you can bookmark them or send them to a colleague. If the
  link or a reload asks you to sign in first, the inbox opens with those filters afterwards. A value
  in the address that is not valid, such as an unknown status, is left out.
- *Manage* opens the detail of that incident, and *Back to the inbox* returns to the same filters
  and page.
- When no incident matches, the page says so and offers *Clear filters*.
- Everything works with the keyboard: *Tab* moves between the filters, links and buttons, *Enter*
  opens them and *Space* ticks a status. A day can be typed; it applies once its year has four
  digits. After *Clear filters*, the focus goes to the first status.

### Incident detail

- **Report:** the description, category, priority and why it was given, when it was reported, its
  last change and, once closed, when it was closed.
- **Photos:** the photos attached to the incident, each with its file name, or *No photos were
  attached.* They are only shown inside the signed-in app; there is no public link to them.
- **Location:** address, neighbourhood, district, coordinates and the accuracy the phone reported
  (for example *within 12.5 m*). A value that is not known says *Not available*: it is never left
  empty or guessed.
- **History:** a timeline of every change of status, oldest first, with its date and time, who made
  it, their role and the reason. Entries the citizen does not see are marked *Internal*. The changes
  of category and priority appear among them as *Classification changed*, with who made them and
  each new value next to the one it replaced; they are always internal. If they cannot be loaded,
  the status changes stay on screen with *Try again* for the rest.
- **Actions:** the actions allowed for the status of the incident. When there are none, as on a
  rejected or closed incident, it says *No actions are available for this incident.* Planned: #48,
  #49, #50, #52.
- **Classification:** choose the category and the priority, write why the incident has that
  priority and press *Save classification*. A priority always needs its reason; you can change
  only the category, only the priority or both. The button stays disabled until you change
  something, and the page says *Classification saved.* only once the server has kept it; the inbox
  shows the new values when you go back to it.
  - If someone else changed the incident since you opened it, nothing is saved: press *Reload the
    incident*, check the new values and save again. What you typed is kept.
  - If the server cannot be reached or answers with an error, nothing is saved and what you typed
    is kept, so you can save again.

*Back to the inbox* returns to the list as you left it. An incident that does not exist shows
*Incident not found*.

Dates are shown in Málaga time.

### When something fails

If the incidents cannot be loaded, the page says so and offers *Try again*; it says whether the
server could not be reached or answered with an error. The app retries a few times on its own
before showing it, and the filters stay as they were. If the categories or departments cannot be
loaded, that filter says so and is disabled; the other filters keep working.

In the incident detail, if the photos cannot be listed, the *Photos* section says so with *Try
again* and the rest of the detail stays. If one photo cannot be loaded, its tile says *This photo
could not be loaded.* with its own *Try again*, and the other photos still show.

## 🔧 Technician (Web)

The menu shows *Work queue*, where you land.

### Work queue

The incidents assigned to you now, newest first, a page at a time, with the same columns as the
operator's inbox. It opens on the work still to do: the statuses *Assigned* and *In progress* are
ticked. Tick other statuses, or *Clear filters*, to see the incidents you resolved.

- **Filters:** the same as in the [inbox](#inbox), and they work the same way: they combine, they
  are part of the address and the page goes back to the first one. They only search your own
  incidents.
- *Open* opens the detail of that incident, and *Back to the work queue* returns to the same
  filters and page.
- An incident leaves the queue when it is assigned to someone else.

### Incident detail

The report, its photos, the location and the history, as the operator sees them (see
[Incident detail](#incident-detail)), and the actions of the technician:

- **Accept the work**, on an *Assigned* incident: press *Accept* when you start working on it. It
  becomes *In progress*, and the citizen sees it.
- **Resolve**, on an incident *In progress*: write the *Resolution notes* (what was done, up to
  2,000 characters) and press *Mark as resolved*. The notes are required. The citizen sees that it
  is resolved, not your notes.
- On any other status it says *No actions are available for this incident.*

The page says *Incident accepted: it is now in progress.* or *Incident resolved.* only once the
server has kept the change. If it was not kept, nothing changed and your notes are still there:

- Someone else changed the incident since you opened it, or it no longer allows the action:
  press *Reload the incident* and check it before trying again.
- It is no longer assigned to you: *Reload the incident* shows *Incident not found*.
- The server cannot be reached or answers with an error: try again.

An incident that is not assigned to you, opened from a link, shows *Incident not found*. Planned:
comments (#50).

## 🛡️ Administrator (Web)

The menu shows *Users*, where you land, *Inbox* and *Reports*.

- **Inbox and incident detail:** an administrator can do everything an operator can
  ([ADR-0020](adr/0020-incident-lifecycle-transitions.md)), so see
  [Municipal Operator](#-municipal-operator-web).
- **Users:** planned: #40.
- **Audit log:** planned: #41.
- **Reports:** the same screen as the analyst's, see [Analyst](#-analyst-web).

## 📊 Analyst (Web)

The menu shows *Reports*, where you land. A report counts the incidents reported in a period,
with the status they have now, and never shows who reported them or where exactly.

1. Choose *From* and *To (included)*, two days of Málaga time, and how to *Group by*: status,
   category, priority, department or district. Then choose *Show report*; choosing it again with
   the same days brings the figures up to date. The screen opens on the last 30 days, grouped by
   status.
   A period has at most 366 days, both included. The limit is counted in hours, so a period of
   exactly 366 days that starts in summer time and ends in winter time is one hour too long:
   choose one day less.
2. Each row is a group, with its *Total* and how many are *Open* (any status that is not final,
   *Resolved* included), *Closed* and *Rejected*. *Median hours to close* and *95th percentile
   hours* count only the closed incidents; a dash means that none of the group is closed.
3. *Not available* gathers the incidents without that information: no category, no priority yet,
   no active assignment to a department, or no district. The district of a new report is not
   filled in yet (Planned: #59), so grouping by district puts those reports under *Not
   available*.
4. *Download CSV* saves the report on the screen as a file, with the same rows. The file
   separates its columns with commas and writes decimals with a dot. A spreadsheet in Spanish
   separates columns with semicolons, so opening the file with a double click puts each whole
   line in the first column: open it with *Data > From Text/CSV* instead, with a comma as the
   delimiter and a dot as the decimal separator.

If the days are not valid, the message says why under the dates and nothing is asked. If the
report or the file cannot be loaded, the page says so: *Try again* reloads the report, and
*Download CSV* can be chosen again; no file is saved when the download fails.

## ✍️ Keeping This Guide Up to Date

A pull request that adds or changes a screen or an action updates its section here, removes its
`Planned` mark and, when a role has all its screens, changes its status in the summary (*When a pull
request is done* in the [contributing guide](../CONTRIBUTING.md)). Write what a user sees and does,
with the words of the screen. Leave out how it is built (the [development guide](development.md)),
what is planned (the [requirements](requirements.md)) and limits that another document owns, such
as the sign-in attempts (the [security guide](security.md)): link to them instead.
