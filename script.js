document.documentElement.classList.add("js-enabled");

const navToggle = document.querySelector(".nav-toggle");
const navLinks = document.querySelector(".nav-links");

if (navToggle && navLinks) {
  navToggle.addEventListener("click", () => {
    const isOpen = navLinks.classList.toggle("open");
    navToggle.setAttribute("aria-expanded", String(isOpen));
  });

  navLinks.querySelectorAll("a").forEach((link) => {
    link.addEventListener("click", () => {
      navLinks.classList.remove("open");
      navToggle.setAttribute("aria-expanded", "false");
    });
  });
}

const revealObserver = new IntersectionObserver(
  (entries) => {
    entries.forEach((entry) => {
      if (entry.isIntersecting) {
        entry.target.classList.add("in-view");
        revealObserver.unobserve(entry.target);
      }
    });
  },
  { threshold: 0.14 }
);

document.querySelectorAll(".reveal").forEach((item) => {
  const top = item.getBoundingClientRect().top;
  if (top < window.innerHeight * 1.1) {
    item.classList.add("in-view");
    return;
  }
  revealObserver.observe(item);
});

const screenShots = Array.from(document.querySelectorAll(".screen-shot"));
let currentShot = 0;

if (screenShots.length > 1) {
  setInterval(() => {
    screenShots[currentShot].classList.remove("active");
    currentShot = (currentShot + 1) % screenShots.length;
    screenShots[currentShot].classList.add("active");
  }, 3200);
}

document.querySelectorAll('a[download]').forEach((button) => {
  button.addEventListener("click", () => {
    button.classList.add("is-downloading");
    setTimeout(() => button.classList.remove("is-downloading"), 900);
  });
});
