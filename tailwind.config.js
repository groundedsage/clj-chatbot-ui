module.exports = {
  content: ["./src/**/*", "./resources/public/index.html"],
  darkMode: 'class',
  theme: {
    extend: {},
  },
  variants: {
    extend: {
      visibility: ['group-hover'],
    },
  },
  // plugins: [require('@tailwindcss/typography')],
};
