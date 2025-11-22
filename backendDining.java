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

