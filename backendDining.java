**package.json:**
```json
{
  "name": "princeton-dining-backend",
  "version": "1.0.0",
  "description": "Backend for Princeton Dining Finder",
  "main": "server.js",
  "scripts": {
    "start": "node server.js",
    "dev": "nodemon server.js"
  },
  "dependencies": {
    "express": "^4.18.2",
    "cors": "^2.8.5",
    "axios": "^1.6.0",
    "cheerio": "^1.0.0-rc.12",
    "node-cache": "^5.1.2"
  },
  "devDependencies": {
    "nodemon": "^3.0.1"
  }
}
```

**server.js:**
```javascript
const express = require('express');
const cors = require('cors');
const axios = require('axios');
const cheerio = require('cheerio');
const NodeCache = require('node-cache');

const app = express();
const PORT = 3000;

// Cache menu data for 1 hour (3600 seconds)
const cache = new NodeCache({ stdTTL: 3600 });

app.use(cors());
app.use(express.json());
app.use(express.static('public')); // Serve your HTML file from here

// Dining hall mappings
const DINING_HALLS = {
    '1': 'Frist',
    '2': 'Whitman',
    '3': 'Butler',
    '4': 'CJL',
    '5': 'Forbes',
    '640': 'Graduate College'
};

const MEAL_PERIODS = {
    '0': 'breakfast',
    '1': 'lunch', 
    '2': 'dinner',
    '3': 'late night'
};

// Function to scrape a single dining hall's menu
async function scrapeDiningHall(locationId, date) {
    try {
        const url = `https://menus.princeton.edu/dining/_Foodpro/online-menu/indexlocationmenu.asp`;
        
        const response = await axios.get(url, {
            params: {
                locationNum: locationId,
                dtdate: date, // Format: MM/DD/YYYY
                mealName: ''
            },
            headers: {
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'
            }
        });

        const $ = cheerio.load(response.data);
        const menuData = {};

        // Find all meal period sections
        $('.accordion').each((i, accordion) => {
            const mealName = $(accordion).find('.accordion-toggle').text().trim().toLowerCase();
            
            if (!menuData[mealName]) {
                menuData[mealName] = {
                    entrees: [],
                    sides: [],
                    desserts: [],
                    other: []
                };
            }

            // Find all menu items in this meal period
            $(accordion).find('.menu-item, .item').each((j, item) => {
                const itemName = $(item).text().trim();
                const category = $(item).closest('.menu-category').find('.category-title').text().trim().toLowerCase();

                if (itemName && itemName.length > 0) {
                    // Categorize items
                    if (category.includes('entree') || category.includes('grill') || category.includes('main')) {
                        menuData[mealName].entrees.push(itemName);
                    } else if (category.includes('side') || category.includes('vegetable')) {
                        menuData[mealName].sides.push(itemName);
                    } else if (category.includes('dessert') || category.includes('sweet')) {
                        menuData[mealName].desserts.push(itemName);
                    } else {
                        menuData[mealName].other.push(itemName);
                    }
                }
            });
        });

        return menuData;
    } catch (error) {
        console.error(`Error scraping dining hall ${locationId}:`, error.message);
        return null;
    }
}

// Alternative scraping approach if the first one doesn't work
async function scrapeDiningHallAlternative(locationId, date) {
    try {
        // Try the direct menu page
        const url = `https://menus.princeton.edu/dining/_Foodpro/online-menu/menupage.asp`;
        
        const response = await axios.get(url, {
            params: {
                locationNum: locationId,
                dtdate: date,
                sName: ''
            },
            headers: {
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'
            }
        });

        const $ = cheerio.load(response.data);
        const menuData = {};

        // Look for menu structure - this varies by Princeton's actual HTML
        $('table.menu-table, div.menu-section').each((i, section) => {
            const mealHeader = $(section).find('h2, h3, .meal-name').first().text().trim().toLowerCase();
            let mealName = 'lunch'; // default
            
            if (mealHeader.includes('breakfast')) mealName = 'breakfast';
            else if (mealHeader.includes('lunch') || mealHeader.includes('brunch')) mealName = 'lunch';
            else if (mealHeader.includes('dinner')) mealName = 'dinner';
            else if (mealHeader.includes('late')) mealName = 'late night';

            if (!menuData[mealName]) {
                menuData[mealName] = {
                    entrees: [],
                    sides: [],
                    desserts: [],
                    other: []
                };
            }

            // Extract all food items
            $(section).find('td, div.item, span.item-name').each((j, item) => {
                const itemName = $(item).text().trim();
                
                if (itemName && itemName.length > 2 && !itemName.match(/^(Entree|Side|Dessert|Breakfast|Lunch|Dinner)/i)) {
                    // Simple categorization based on keywords
                    const lowerItem = itemName.toLowerCase();
                    
                    if (lowerItem.match(/chicken|beef|fish|pork|turkey|salmon|steak|burger|pizza|pasta/)) {
                        menuData[mealName].entrees.push(itemName);
                    } else if (lowerItem.match(/cake|pie|cookie|ice cream|brownie|pudding|dessert/)) {
                        menuData[mealName].desserts.push(itemName);
                    } else if (lowerItem.match(/salad|vegetable|potato|rice|fries|beans/)) {
                        menuData[mealName].sides.push(itemName);
                    } else {
                        menuData[mealName].other.push(itemName);
                    }
                }
            });
        });

        return menuData;
    } catch (error) {
        console.error(`Error in alternative scraping for ${locationId}:`, error.message);
        return null;
    }
}

// Get today's date in MM/DD/YYYY format
function getTodayDate() {
    const today = new Date();
    const month = String(today.getMonth() + 1).padStart(2, '0');
    const day = String(today.getDate()).padStart(2, '0');
    const year = today.getFullYear();
    return `${month}/${day}/${year}`;
}

// Get date for specific day of week
function getDateForDay(dayOfWeek) {
    const days = ['sunday', 'monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday'];
    const today = new Date();
    const todayDayIndex = today.getDay();
    const targetDayIndex = days.indexOf(dayOfWeek.toLowerCase());
    
    if (targetDayIndex === -1) return getTodayDate();
    
    const daysUntilTarget = (targetDayIndex - todayDayIndex + 7) % 7;
    const targetDate = new Date(today);
    targetDate.setDate(today.getDate() + daysUntilTarget);
    
    const month = String(targetDate.getMonth() + 1).padStart(2, '0');
    const day = String(targetDate.getDate()).padStart(2, '0');
    const year = targetDate.getFullYear();
    return `${month}/${day}/${year}`;
}

// API endpoint to get all menus
app.get('/api/menus', async (req, res) => {
    const { day } = req.query;
    const date = day ? getDateForDay(day) : getTodayDate();
    
    const cacheKey = `menus_${date}`;
    const cachedData = cache.get(cacheKey);
    
    if (cachedData) {
        console.log('Returning cached menu data');
        return res.json(cachedData);
    }

    console.log(`Fetching menus for date: ${date}`);
    
    const allMenus = {};
    const scrapePromises = [];

    // Scrape all dining halls
    for (const [locationId, hallName] of Object.entries(DINING_HALLS)) {
        scrapePromises.push(
            scrapeDiningHall(locationId, date)
                .then(menu => {
                    if (menu && Object.keys(menu).length > 0) {
                        allMenus[hallName] = menu;
                    }
                    return scrapeDiningHallAlternative(locationId, date);
                })
                .then(altMenu => {
                    if (altMenu && Object.keys(altMenu).length > 0 && !allMenus[hallName]) {
                        allMenus[hallName] = altMenu;
                    }
                })
                .catch(err => {
                    console.error(`Failed to scrape ${hallName}:`, err.message);
                })
        );
    }

    try {
        await Promise.all(scrapePromises);
        
        // If we got data, cache it
        if (Object.keys(allMenus).length > 0) {
            cache.set(cacheKey, allMenus);
            res.json(allMenus);
        } else {
            // Return sample data if scraping fails
            res.json(getSampleData());
        }
    } catch (error) {
        console.error('Error fetching menus:', error);
        res.status(500).json({ error: 'Failed to fetch menus', sample: getSampleData() });
    }
});

// API endpoint to search for food items
app.get('/api/search', async (req, res) => {
    const { query, day } = req.query;
    
    if (!query) {
        return res.status(400).json({ error: 'Query parameter is required' });
    }

    const date = day ? getDateForDay(day) : getTodayDate();
    const cacheKey = `menus_${date}`;
    let allMenus = cache.get(cacheKey);

    if (!allMenus) {
        // Fetch menus if not cached
        const response = await axios.get(`http://localhost:${PORT}/api/menus?day=${day || ''}`);
        allMenus = response.data;
    }

    const results = [];
    const searchTerm = query.toLowerCase();

    for (const [hall, meals] of Object.entries(allMenus)) {
        for (const [meal, categories] of Object.entries(meals)) {
            for (const [category, items] of Object.entries(categories)) {
                items.forEach(item => {
                    if (item.toLowerCase().includes(searchTerm)) {
                        results.push({
                            item: item,
                            hall: hall,
                            meal: meal,
                            category: category,
                            day: day || 'today'
                        });
                    }
                });
            }
        }
    }

    res.json(results);
});

// Fallback sample data
function getSampleData() {
    return {
        "Frist": {
            "breakfast": {
                "entrees": ["Scrambled Eggs", "Bacon", "Pancakes"],
                "sides": ["Hash Browns", "Fresh Fruit"],
                "desserts": ["Yogurt"],
                "other": []
            },
            "lunch": {
                "entrees": ["Chicken Sandwich", "Grilled Chicken"],
                "sides": ["Caesar Salad", "French Fries"],
                "desserts": ["Apple Pie"],
                "other": ["Tomato Soup"]
            },
            "dinner": {
                "entrees": ["Grilled Salmon", "Roast Chicken", "Pasta Primavera"],
                "sides": ["Steamed Broccoli"],
                "desserts": ["Tiramisu"],
                "other": []
            }
        },
        "Whitman": {
            "breakfast": {
                "entrees": ["Omelets", "Sausage", "French Toast"],
                "sides": ["Bagels", "Oatmeal"],
                "desserts": [],
                "other": ["Fresh Juice"]
            },
            "lunch": {
                "entrees": ["Grilled Chicken", "Turkey Wrap"],
                "sides": ["Greek Salad", "Roasted Vegetables"],
                "desserts": ["Chocolate Cake"],
                "other": ["Minestrone Soup"]
            },
            "dinner": {
                "entrees": ["Beef Tenderloin", "Baked Cod", "Vegetable Lasagna"],
                "sides": ["Green Beans"],
                "desserts": ["Cheesecake"],
                "other": []
            }
        }
    };
}

app.listen(PORT, () => {
    console.log(`Server running on http://localhost:${PORT}`);
    console.log('Menu data will be cached for 1 hour');
});
```

## 2. Updated Frontend (save as public/index.html)

Replace the script section in your HTML with this updated version:

```javascript
<script>
    const API_URL = 'http://localhost:3000/api';
    
    let currentPollData = null;
    let pollVotes = [];

    function switchTab(tab) {
        document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
        document.querySelectorAll('.service').forEach(s => s.classList.remove('active'));
        
        event.target.classList.add('active');
        document.getElementById(tab).classList.add('active');
    }

    async function searchFood() {
        const query = document.getElementById('searchInput').value.trim().toLowerCase();
        const resultsContainer = document.getElementById('searchResults');
        
        if (!query) {
            resultsContainer.innerHTML = '<div class="error">Please enter a food item to search.</div>';
            return;
        }

        resultsContainer.innerHTML = '<div class="loading"><div class="spinner"></div>Searching menus...</div>';

        try {
            const response = await fetch(`${API_URL}/search?query=${encodeURIComponent(query)}`);
            const results = await response.json();

            if (results.error) {
                throw new Error(results.error);
            }

            if (results.length === 0) {
                resultsContainer.innerHTML = `<div class="error">No results found for "${query}". Try searching for common items like pizza, chicken, or salad.</div>`;
            } else {
                let html = `<h3 style="margin-bottom: 20px;">Found ${results.length} result${results.length > 1 ? 's' : ''} for "${query}"</h3>`;
                results.forEach(result => {
                    html += `
                        <div class="result-card">
                            <h3>${result.item}</h3>
                            <div class="result-info">
                                <span class="info-tag">🏢 ${result.hall}</span>
                                <span class="info-tag">📅 ${result.day}</span>
                                <span class="info-tag">🍽️ ${result.meal}</span>
                                <span class="info-tag">🍴 ${result.category}</span>
                            </div>
                        </div>
                    `;
                });
                resultsContainer.innerHTML = html;
            }
        } catch (error) {
            console.error('Search error:', error);
            resultsContainer.innerHTML = `<div class="error">Error searching menus. Please try again. ${error.message}</div>`;
        }
    }

    async function createPoll() {
        const day = document.getElementById('pollDay').value;
        const meal = document.getElementById('pollMeal').value;
        
        const pollId = Math.random().toString(36).substr(2, 9);
        const pollUrl = `${window.location.href.split('?')[0]}?poll=${pollId}&day=${day}&meal=${meal}`;
        
        currentPollData = {
            id: pollId,
            day: day,
            meal: meal,
            votes: []
        };
        
        localStorage.setItem(`poll_${pollId}`, JSON.stringify(currentPollData));
        
        const container = document.getElementById('pollLinkContainer');
        container.innerHTML = `
            <div class="poll-link">
                <h4>Share this link with your friends:</h4>
                <input type="text" value="${pollUrl}" readonly onclick="this.select()">
                <button onclick="copyPollLink('${pollUrl}')" style="margin-top: 10px;">Copy Link</button>
            </div>
        `;
    }

    function copyPollLink(url) {
        navigator.clipboard.writeText(url).then(() => {
            alert('Poll link copied to clipboard!');
        });
    }

    function loadPollFromUrl() {
        const urlParams = new URLSearchParams(window.location.search);
        const pollId = urlParams.get('poll');
        const day = urlParams.get('day');
        const meal = urlParams.get('meal');
        
        if (pollId && day && meal) {
            switchTab('poll');
            document.querySelectorAll('.tab')[1].classList.add('active');
            showPollVoting(pollId, day, meal);
        }
    }

    async function showPollVoting(pollId, day, meal) {
        document.getElementById('pollCreation').style.display = 'none';
        document.getElementById('pollVoting').style.display = 'block';
        
        try {
            const response = await fetch(`${API_URL}/menus?day=${day}`);
            const menuData = await response.json();
            
            let html = '';
            
            for (const [hall, meals] of Object.entries(menuData)) {
                if (meals[meal]) {
                    html += `
                        <div class="dining-hall-option">
                            <h3>${hall} Dining Hall</h3>
                            <div class="menu-items">
                    `;
                    
                    const allItems = [
                        ...meals[meal].entrees || [],
                        ...meals[meal].sides || [],
                        ...meals[meal].desserts || [],
                        ...meals[meal].other || []
                    ];
                    
                    allItems.forEach((item, index) => {
                        const itemId = `${hall}_${index}`;
                        html += `
                            <div class="menu-item">
                                <input type="checkbox" id="${itemId}" value="${hall}|${item}">
                                <label for="${itemId}">${item}</label>
                            </div>
                        `;
                    });
                    
                    html += `
                            </div>
                        </div>
                    `;
                }
            }
            
            if (html === '') {
                html = '<div class="error">No menu data available for this meal period.</div>';
            }
            
            document.getElementById('pollOptions').innerHTML = html;
            currentPollData = { id: pollId, day: day, meal: meal };
        } catch (error) {
            console.error('Error loading poll:', error);
            document.getElementById('pollOptions').innerHTML = '<div class="error">Error loading menu data.</div>';
        }
    }

    function submitVote() {
        const checkboxes = document.querySelectorAll('#pollOptions input[type="checkbox"]:checked');
        
        if (checkboxes.length === 0) {
            alert('Please select at least one item!');
            return;
        }
        
        const votes = {};
        checkboxes.forEach(cb => {
            const [hall, item] = cb.value.split('|');
            if (!votes[hall]) votes[hall] = [];
            votes[hall].push(item);
        });
        
        const pollData = JSON.parse(localStorage.getItem(`poll_${currentPollData.id}`)) || currentPollData;
        if (!pollData.votes) pollData.votes = [];
        pollData.votes.push(votes);
        localStorage.setItem(`poll_${currentPollData.id}`, JSON.stringify(pollData));
        
        showPollResults(pollData);
    }

    function showPollResults(pollData) {
        document.getElementById('pollVoting').style.display = 'none';
        
        const hallScores = {};
        pollData.votes.forEach(vote => {
            Object.keys(vote).forEach(hall => {
                hallScores[hall] = (hallScores[hall] || 0) + vote[hall].length;
            });
        });
        
        const sortedHalls = Object.entries(hallScores).sort((a, b) => b[1] - a[1]);
        const winner = sortedHalls[0];
        const totalVotes = Object.values(hallScores).reduce((a, b) => a + b, 0);
        
        let html = `
            <div class="poll-results">
                <div class="winner">
                    🏆 Winner: ${winner[0]} Dining Hall!
                </div>
                <div class="vote-breakdown">
                    <h3 style="margin-bottom: 20px;">Vote Breakdown (${pollData.votes.length} voter${pollData.votes.length > 1 ? 's' : ''})</h3>
        `;
        
        sortedHalls.forEach(([hall, score]) => {
            const percentage = (score / totalVotes * 100).toFixed(1);
            html += `
                <div class="vote-bar">
                    <div class="vote-bar-label">
                        <span>${hall}</span>
                        <span>${score} votes</span>
                    </div>
                    <div class="vote-bar-fill">
                        <div class="vote-bar-fill-inner" style="width: ${percentage}%">
                            ${percentage}%
                        </div>
                    </div>
                </div>
            `;
        });
        
        html += `
                </div>
                <button onclick="location.reload()" style="margin-top: 20px;">Create New Poll</button>
            </div>
        `;
        
        document.getElementById('pollResultsContainer').innerHTML = html;
    }

    window.addEventListener('load', loadPollFromUrl);

    document.getElementById('searchInput').addEventListener('keypress', function(e) {
        if (e.key === 'Enter') {
            searchFood();
        }
    });
</script>
```