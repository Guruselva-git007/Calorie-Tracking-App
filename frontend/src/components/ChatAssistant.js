import React, { useEffect, useMemo, useRef, useState } from 'react';
import { automationAPI, describeApiError, dishAPI, entryAPI, ingredientAPI, supportAPI } from '../services/api';

const LOG_UNIT_SET = new Set(['g', 'kg', 'ml', 'l', 'oz', 'lb', 'serving', 'count']);

const getTimestamp = () =>
  new Date().toLocaleTimeString([], {
    hour: '2-digit',
    minute: '2-digit'
  });

const initialBotMessages = [
  {
    id: 'welcome-1',
    role: 'bot',
    text: 'Hi, I am your app assistant. I can log foods, show today summary, trigger refresh jobs, and help with support.',
    at: getTimestamp()
  },
  {
    id: 'welcome-2',
    role: 'bot',
    text: 'Try: "log 2 dosa", "summary", "refresh sweets", "open tools".',
    at: getTimestamp()
  }
];

const normalizeText = (value) => String(value || '').trim();

const roundValue = (value, digits = 1) => {
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) {
    return 0;
  }
  const scale = 10 ** digits;
  return Math.round(parsed * scale) / scale;
};

const normalizeLogUnit = (value) => {
  const unit = normalizeText(value).toLowerCase();
  if (unit === 'servings') {
    return 'serving';
  }
  if (unit === 'counts' || unit === 'piece' || unit === 'pieces') {
    return 'count';
  }
  if (unit === 'lbs' || unit === 'pound' || unit === 'pounds') {
    return 'lb';
  }
  return LOG_UNIT_SET.has(unit) ? unit : 'g';
};

const toEstimatedGrams = (quantity, unit) => {
  const value = Number(quantity);
  if (!Number.isFinite(value) || value <= 0) {
    return 100;
  }

  switch (unit) {
    case 'kg':
      return value * 1000;
    case 'ml':
      return value;
    case 'l':
      return value * 1000;
    case 'oz':
      return value * 28.3495;
    case 'lb':
      return value * 453.592;
    case 'serving':
      return value * 100;
    case 'count':
      return value * 100;
    case 'g':
    default:
      return value;
  }
};

const parseLogInstruction = (text) => {
  const raw = normalizeText(text);
  const match = raw.match(/^(?:log|add)\s+(.+)$/i);
  if (!match?.[1]) {
    return null;
  }

  let body = match[1].trim();
  let quantity = 100;
  let unit = 'g';

  const valueWithUnit = body.match(/(?:^|\s)(\d+(?:\.\d+)?)\s*(kg|g|ml|l|oz|lb|lbs|pound|pounds|serving|servings|count|counts|piece|pieces)\b/i);
  if (valueWithUnit) {
    quantity = Number(valueWithUnit[1]);
    unit = normalizeLogUnit(valueWithUnit[2]);
    body = body.replace(valueWithUnit[0], ' ').replace(/\s+/g, ' ').trim();
  } else {
    const leadingCount = body.match(/^(\d+(?:\.\d+)?)\s+(.+)$/);
    if (leadingCount?.[1] && leadingCount?.[2]) {
      quantity = Number(leadingCount[1]);
      unit = 'count';
      body = leadingCount[2].trim();
    }
  }

  const query = body.replace(/\s+/g, ' ').trim();
  if (!query) {
    return null;
  }

  return {
    query,
    quantity: Number.isFinite(quantity) && quantity > 0 ? quantity : 100,
    unit
  };
};

const pickIngredientMatch = (items, query) => {
  if (!Array.isArray(items) || !items.length) {
    return null;
  }

  const normalizedQuery = normalizeText(query).toLowerCase();
  const exact = items.find((item) => normalizeText(item?.name).toLowerCase() === normalizedQuery);
  if (exact) {
    return exact;
  }

  const startsWith = items.find((item) => normalizeText(item?.name).toLowerCase().startsWith(normalizedQuery));
  if (startsWith) {
    return startsWith;
  }

  return items[0] || null;
};

const pickDishMatch = (items, query) => {
  if (!Array.isArray(items) || !items.length) {
    return null;
  }

  const normalizedQuery = normalizeText(query).toLowerCase();
  const exact = items.find((item) => normalizeText(item?.name).toLowerCase() === normalizedQuery);
  if (exact) {
    return exact;
  }

  const startsWith = items.find((item) => normalizeText(item?.name).toLowerCase().startsWith(normalizedQuery));
  if (startsWith) {
    return startsWith;
  }

  return items[0] || null;
};

const resolveAutomationTask = (normalizedText) => {
  if (normalizedText.includes('open food') || normalizedText.includes('barcode')) {
    return 'open-food-facts';
  }
  if (normalizedText.includes('world') || normalizedText.includes('cuisine')) {
    return 'world-cuisines';
  }
  if (normalizedText.includes('sweet') || normalizedText.includes('dessert')) {
    return 'sweets-desserts';
  }
  if (normalizedText.includes('image')) {
    return 'images';
  }
  if (normalizedText.includes('correct') || normalizedText.includes('fix dataset')) {
    return 'dataset-correction';
  }
  return 'manual-next';
};

const ChatAssistant = ({ user, activePage, onNavigate, onOpenQuickLogger }) => {
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [mode, setMode] = useState('general');
  const [input, setInput] = useState('');
  const [messages, setMessages] = useState(initialBotMessages);
  const [helpData, setHelpData] = useState(null);
  const messagesRef = useRef(null);

  useEffect(() => {
    if (!open || !messagesRef.current) {
      return;
    }
    messagesRef.current.scrollTop = messagesRef.current.scrollHeight;
  }, [messages, open]);

  const appendMessage = (role, text) => {
    const cleanText = normalizeText(text);
    if (!cleanText) {
      return;
    }
    setMessages((previous) => [
      ...previous,
      {
        id: `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
        role,
        text: cleanText,
        at: getTimestamp()
      }
    ]);
  };

  const modeLabel = useMemo(() => {
    if (mode === 'suggest') {
      return 'Suggestion mode';
    }
    if (mode === 'feedback') {
      return 'Feedback mode';
    }
    return 'Task mode';
  }, [mode]);

  const openPage = (page) => {
    onNavigate?.(page);
    appendMessage('bot', `Opened ${page} page.`);
  };

  const openQuickLogger = () => {
    onOpenQuickLogger?.();
    appendMessage('bot', 'Opened Quick Logger on Home.');
  };

  const triggerAutomation = async (task = 'manual-next') => {
    setBusy(true);
    try {
      const response = await automationAPI.trigger(task);
      const taskKey = response?.data?.taskKey || task;
      const jobId = response?.data?.jobId;
      if (jobId) {
        appendMessage('bot', `Refresh started: ${taskKey}. Job: ${jobId}`);
      } else {
        appendMessage('bot', `Automation response: ${response?.data?.message || 'Triggered.'}`);
      }
    } catch (errorResponse) {
      appendMessage('bot', describeApiError(errorResponse, 'Could not trigger automation right now.'));
    } finally {
      setBusy(false);
    }
  };

  const showAutomationStatus = async () => {
    setBusy(true);
    try {
      const response = await automationAPI.status();
      const payload = response?.data || {};
      const enabled = payload?.enabled ? 'enabled' : 'disabled';
      const internet = payload?.internetReachable ? 'online' : 'offline';
      const tasks = Array.isArray(payload?.tasks) ? payload.tasks : [];
      const topTasks = tasks
        .slice(0, 3)
        .map((task) => `${task.taskKey}: ${task.lastStatus || 'NA'}`)
        .join(' | ');
      appendMessage(
        'bot',
        `Automation ${enabled}, internet ${internet}. ${payload?.lastCycleMessage || ''}${topTasks ? `\nTasks: ${topTasks}` : ''}`
      );
    } catch (errorResponse) {
      appendMessage('bot', describeApiError(errorResponse, 'Unable to fetch automation status.'));
    } finally {
      setBusy(false);
    }
  };

  const showHelp = async () => {
    if (helpData) {
      appendMessage('bot', `${helpData.tips.join(' | ')}`);
      return;
    }

    setBusy(true);
    try {
      const response = await supportAPI.quickHelp();
      const payload = response?.data || {};
      const tips = Array.isArray(payload?.tips) ? payload.tips : [];
      const quickTasks = Array.isArray(payload?.quickTasks) ? payload.quickTasks : [];
      const supportMessage = normalizeText(payload?.supportMessage);
      setHelpData({
        tips,
        quickTasks,
        supportMessage
      });

      const lines = [];
      if (tips.length) {
        lines.push(`Tips: ${tips.join(' | ')}`);
      }
      if (quickTasks.length) {
        lines.push(`Quick tasks: ${quickTasks.join(', ')}`);
      }
      if (supportMessage) {
        lines.push(supportMessage);
      }
      appendMessage('bot', lines.join('\n'));
    } catch (errorResponse) {
      appendMessage('bot', describeApiError(errorResponse, 'Support tips are unavailable right now.'));
    } finally {
      setBusy(false);
    }
  };

  const runSuggestionSearch = async (query) => {
    const search = normalizeText(query);
    if (!search) {
      appendMessage('bot', 'Type a food name to get suggestions.');
      return;
    }

    setBusy(true);
    try {
      const [dishRes, ingredientRes] = await Promise.all([
        dishAPI.suggest(search, { limit: 5 }),
        ingredientAPI.search(search, { limit: 5 })
      ]);

      const dishes = Array.isArray(dishRes?.data) ? dishRes.data : [];
      const ingredients = Array.isArray(ingredientRes?.data) ? ingredientRes.data : [];

      const dishNames = dishes.map((item) => item?.name).filter(Boolean).slice(0, 5);
      const ingredientNames = ingredients.map((item) => item?.name).filter(Boolean).slice(0, 5);

      if (!dishNames.length && !ingredientNames.length) {
        appendMessage('bot', 'No strong matches yet. Try a broader name or trigger dataset refresh.');
        return;
      }

      const responseLines = [];
      if (dishNames.length) {
        responseLines.push(`Dishes: ${dishNames.join(', ')}`);
      }
      if (ingredientNames.length) {
        responseLines.push(`Ingredients: ${ingredientNames.join(', ')}`);
      }
      appendMessage('bot', responseLines.join('\n'));
    } catch (errorResponse) {
      appendMessage('bot', describeApiError(errorResponse, 'Could not fetch suggestions now.'));
    } finally {
      setBusy(false);
    }
  };

  const showTodaySummary = async () => {
    if (!user?.id) {
      appendMessage('bot', 'Login required for daily summary.');
      return;
    }

    setBusy(true);
    try {
      const response = await entryAPI.getSummary(user.id);
      const summary = response?.data || {};
      appendMessage(
        'bot',
        `Today (${summary?.date || 'current day'}): ${Math.round(summary?.totalCalories || 0)} cal, remaining ${Math.round(summary?.remainingCalories || 0)} cal, P ${roundValue(summary?.totalProtein)}g, C ${roundValue(summary?.totalCarbs)}g, F ${roundValue(summary?.totalFats)}g, Fi ${roundValue(summary?.totalFiber)}g, entries ${summary?.entryCount || 0}.`
      );
    } catch (errorResponse) {
      appendMessage('bot', describeApiError(errorResponse, 'Unable to load today summary.'));
    } finally {
      setBusy(false);
    }
  };

  const runQuickLogTask = async (rawInstruction) => {
    if (!user?.id) {
      appendMessage('bot', 'Login is required to save entries.');
      return;
    }

    const parsed = parseLogInstruction(rawInstruction);
    if (!parsed) {
      appendMessage('bot', 'Use format: log <food> <amount><unit>. Example: log 2 dosa or log coffee 200 ml.');
      return;
    }

    setBusy(true);
    try {
      const ingredientResponse = await ingredientAPI.search(parsed.query, { limit: 8 });
      const ingredient = pickIngredientMatch(ingredientResponse?.data || [], parsed.query);

      if (ingredient?.id) {
        await entryAPI.createIngredient({
          userId: user.id,
          ingredientId: ingredient.id,
          quantity: parsed.quantity,
          unit: parsed.unit
        });

        const estimatedCalories = (ingredient.caloriesPer100g || 0) * (toEstimatedGrams(parsed.quantity, parsed.unit) / 100);
        onOpenQuickLogger?.();
        appendMessage(
          'bot',
          `Logged ${roundValue(parsed.quantity, 2)} ${parsed.unit} of ${ingredient.name}. Estimated ${Math.round(estimatedCalories)} cal.`
        );
        return;
      }

      const dishResponse = await dishAPI.suggest(parsed.query, { limit: 6 });
      const dish = pickDishMatch(dishResponse?.data || [], parsed.query);

      if (dish?.id) {
        const servingBased = parsed.unit === 'serving' || parsed.unit === 'count';
        const servings = servingBased ? Math.max(0.25, Math.min(12, parsed.quantity)) : 1;
        const note = servingBased ? 'Logged by assistant' : `Assistant quantity: ${roundValue(parsed.quantity, 2)} ${parsed.unit}`;

        await entryAPI.createDish({
          userId: user.id,
          dishId: dish.id,
          servings,
          note
        });

        onOpenQuickLogger?.();
        appendMessage(
          'bot',
          `Logged dish ${dish.name}${servingBased ? ` (${roundValue(servings, 2)} serving)` : ''}.`
        );
        return;
      }

      setMode('suggest');
      appendMessage('bot', `Could not auto-log "${parsed.query}" yet. Suggestion mode is enabled for faster selection.`);
    } catch (errorResponse) {
      appendMessage('bot', describeApiError(errorResponse, 'Unable to save quick log right now.'));
    } finally {
      setBusy(false);
    }
  };

  const submitFeedback = async (text) => {
    const message = normalizeText(text);
    if (!message) {
      appendMessage('bot', 'Please type feedback text first.');
      return;
    }

    setBusy(true);
    try {
      const response = await supportAPI.submitFeedback({
        userId: user?.id || null,
        userName: user?.nickname || user?.name || 'Guest',
        email: user?.email || '',
        category: 'chatbot',
        pageContext: activePage,
        source: 'chat-assistant-widget',
        message
      });
      const feedbackId = response?.data?.id;
      appendMessage('bot', feedbackId ? `Feedback saved. Ticket #${feedbackId}` : 'Feedback saved. Thank you.');
      setMode('general');
    } catch (errorResponse) {
      appendMessage('bot', describeApiError(errorResponse, 'Could not save feedback right now.'));
    } finally {
      setBusy(false);
    }
  };

  const handleGeneralRequest = async (text) => {
    const normalized = normalizeText(text).toLowerCase();
    if (!normalized) {
      return;
    }

    if (normalized.startsWith('log ') || normalized.startsWith('add ')) {
      await runQuickLogTask(text);
      return;
    }

    if (normalized.includes('quick') && normalized.includes('log')) {
      openQuickLogger();
      return;
    }
    if (normalized.includes('summary') || normalized.includes('today')) {
      await showTodaySummary();
      return;
    }
    if (normalized.includes('home')) {
      openPage('home');
      return;
    }
    if (normalized.includes('tool')) {
      openPage('tools');
      return;
    }
    if (normalized.includes('setting')) {
      openPage('settings');
      return;
    }
    if (
      normalized.includes('refresh')
      || normalized.includes('update data')
      || normalized.includes('sync')
      || normalized.includes('automation')
    ) {
      if (normalized.includes('status') || normalized.includes('state')) {
        await showAutomationStatus();
        return;
      }
      const task = resolveAutomationTask(normalized);
      await triggerAutomation(task);
      return;
    }
    if (normalized.includes('status') || normalized.includes('health')) {
      await showAutomationStatus();
      return;
    }
    if (normalized.includes('help') || normalized.includes('support')) {
      await showHelp();
      return;
    }
    if (normalized.includes('feedback')) {
      setMode('feedback');
      appendMessage('bot', 'Feedback mode enabled. Type your feedback and send.');
      return;
    }
    if (normalized.includes('suggest') || normalized.includes('food') || normalized.includes('dish')) {
      setMode('suggest');
      const query = normalized.replace('suggest', '').trim();
      if (query) {
        await runSuggestionSearch(query);
      } else {
        appendMessage('bot', 'Suggestion mode enabled. Type a food name.');
      }
      return;
    }

    appendMessage(
      'bot',
      'Supported tasks: log food, show summary, open pages, trigger refresh, food suggestions, help, feedback.'
    );
  };

  const onSend = async (event) => {
    event.preventDefault();
    if (busy) {
      return;
    }

    const text = normalizeText(input);
    if (!text) {
      return;
    }
    setInput('');
    appendMessage('user', text);

    if (mode === 'suggest') {
      await runSuggestionSearch(text);
      return;
    }
    if (mode === 'feedback') {
      await submitFeedback(text);
      return;
    }

    await handleGeneralRequest(text);
  };

  return (
    <div className={`chat-assistant-shell${open ? ' open' : ''}`}>
      <button
        type="button"
        className="chat-assistant-launch"
        onClick={() => setOpen((previous) => !previous)}
        aria-label={open ? 'Close assistant' : 'Open assistant'}
      >
        <span className="chat-assistant-launch-icon" aria-hidden="true">
          AI
        </span>
        <span className="chat-assistant-launch-text">{open ? 'Close' : 'Assistant'}</span>
      </button>

      {open ? (
        <section className="chat-assistant-panel" aria-label="App assistant chat">
          <header className="chat-assistant-header">
            <div>
              <h3>App Assistant</h3>
              <p>{modeLabel}</p>
            </div>
            <button type="button" className="chat-assistant-close" onClick={() => setOpen(false)} aria-label="Close">
              x
            </button>
          </header>

          <div className="chat-assistant-actions">
            <button type="button" onClick={openQuickLogger} disabled={busy}>
              Quick Logger
            </button>
            <button type="button" onClick={showTodaySummary} disabled={busy}>
              Today Summary
            </button>
            <button type="button" onClick={() => setMode('suggest')} disabled={busy}>
              Suggest Foods
            </button>
            <button type="button" onClick={() => triggerAutomation('manual-next')} disabled={busy}>
              Heavy Refresh
            </button>
            <button type="button" onClick={showAutomationStatus} disabled={busy}>
              Automation Status
            </button>
            <button type="button" onClick={showHelp} disabled={busy}>
              Help
            </button>
            <button
              type="button"
              disabled={busy}
              onClick={() => {
                setMode('feedback');
                appendMessage('bot', 'Feedback mode enabled. Type your message and send.');
              }}
            >
              Feedback
            </button>
          </div>

          <div className="chat-assistant-messages" ref={messagesRef}>
            {messages.map((message) => (
              <article key={message.id} className={`chat-assistant-bubble ${message.role === 'user' ? 'user' : 'bot'}`}>
                <p>{message.text}</p>
                <small>{message.at}</small>
              </article>
            ))}
          </div>

          <form className="chat-assistant-input" onSubmit={onSend}>
            <input
              type="text"
              value={input}
              onChange={(event) => setInput(event.target.value)}
              placeholder={
                mode === 'feedback'
                  ? 'Type feedback...'
                  : mode === 'suggest'
                    ? 'Type food name...'
                    : 'Try: log 2 idli | summary | refresh images'
              }
            />
            <button type="submit" disabled={busy}>
              {busy ? '...' : 'Send'}
            </button>
          </form>
        </section>
      ) : null}
    </div>
  );
};

export default ChatAssistant;
