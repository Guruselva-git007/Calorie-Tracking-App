-- Reduce ingredient prices by 35% to reflect more affordable market rates
UPDATE ingredient 
SET average_price_usd = average_price_usd * 0.65
WHERE average_price_usd > 0;

-- Verify the changes
SELECT 'Ingredients updated' as status, COUNT(*) as count FROM ingredient WHERE average_price_usd > 0;
