# 🥗 CalorieTracker — Free & Open Source

> Tracking calories and staying healthy shouldn't be hard. And it definitely shouldn't be behind a paywall.

CalorieTracker is a completely free, open source nutrition and fitness tracker. No subscriptions. No locked features. No BS.

> ⚠️ **Work in Progress** — This project is actively being built as of April 2026. Things will break, change, and improve. Feedback, ideas, and contributions are all welcome — see [Contributing](#contributing) below.

---

## Why This Exists

Most calorie tracking apps lock their best features behind a paywall — barcode scanning, goal setting, detailed macros. This project exists to change that. Whether you're trying to lose weight, build muscle, or just eat better, you deserve tools that actually help you do it without paying a monthly fee.

---

## Features

- 📊 **Calorie & macro tracking** — log daily calories, protein, and weight
- 🎯 **Goal setting** — set daily calorie and protein targets
- 📷 **Barcode scanner** *(coming soon)* — scan any food product to instantly log it
- 🔔 **Notifications** *(planned)* — reminders to log meals and hit your goals
- 📱 **Mobile & web** — works as a web app and mobile app from the same backend

---

## Planned Features & Future Vision

The goal is to keep building this out into a full fitness companion — not just a calorie tracker. Here's what's on the roadmap once the core is polished:

### 🏋️ Workout Tracker
- Log workouts and save them by day (e.g. Pull Day on Monday, Push on Wednesday)
- Schedule your weekly split and track it over time
- See which muscle groups you've been hitting and which you might be neglecting
- Rest day reminders and weekly volume overview

### ⏱️ Built-in Rest Timer
- No more switching to a separate app mid-set
- Simple countdown timer for rest periods
- Consecutive timer sequences — e.g. 30 seconds rest → 1 minute work → repeat
- Customizable intervals saved per workout

### 💬 Open to Ideas
Got a feature you wish your fitness app had but it was locked behind a paywall? Open an issue and suggest it. If it fits the mission — free, useful, no fluff — it'll be considered.

---

## Tech Stack

**Backend**
- Java 21 + Spring Boot
- PostgreSQL (hosted on Neon)
- Spring Data JPA / Hibernate

**Frontend** *(planned)*
- TBD — React (web) / React Native (mobile)

**Other**
- Firebase Cloud Messaging for push notifications *(planned)*
- Open Food Facts API for barcode scanning *(planned)*

---

## Project Status

🚧 **In active development** — backend in progress.

- [x] Entity design (User, UserInformation, Tracking)
- [x] Database setup with Neon PostgreSQL
- [x] DTOs and mappers
- [ ] Service layer
- [ ] REST API endpoints
- [ ] Authentication
- [ ] Barcode scanner integration
- [ ] Frontend (web)
- [ ] Mobile app
- [ ] Push notifications

---

## Getting Started

### Prerequisites
- Java 21+
- Maven
- PostgreSQL database (or a free [Neon](https://neon.tech) account)

### Running Locally

```bash
git clone https://github.com/yourusername/calorie-tracker.git
cd calorie-tracker
```

Set up your `application.properties`:

```properties
spring.datasource.url=jdbc:postgresql://YOUR_DB_URL/neondb?sslmode=require
spring.datasource.username=YOUR_USERNAME
spring.datasource.password=YOUR_PASSWORD
spring.jpa.hibernate.ddl-auto=update
```

```bash
./mvnw spring-boot:run
```

---

## Contributing

Contributions are welcome! This project is built by someone who actually uses it — if you want to help make free fitness tracking better, open a PR or file an issue.

---

## License

MIT — free forever, for everyone.