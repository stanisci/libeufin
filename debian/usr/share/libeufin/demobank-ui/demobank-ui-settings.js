globalThis.talerDemobankSettings = {
  // Only Admin adds users
  allowRegistrations: false,
  bankName: "Taler Bank",
  // Show explainer text and navbar to other demo sites
  showDemoNav: false,
  bankBaseUrl: "http://localhost/demobanks/default/"
};

// Currently this is still required by demobank-ui,
// the above, nicer method doesn't work yet.
localStorage.setItem("bank-base-url", "http://localhost/demobanks/default/")
