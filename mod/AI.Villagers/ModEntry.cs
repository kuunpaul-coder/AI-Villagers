using System.Collections.Concurrent;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using Microsoft.Xna.Framework;
using StardewModdingAPI;
using StardewValley;

namespace AI.Villagers;

public sealed class ModEntry : Mod
{
    // Bypass Android/system HTTP proxies: the bridge is on this device's loopback.
    private static readonly HttpClient Client = new(new HttpClientHandler
    {
        UseProxy = false
    })
    {
        Timeout = TimeSpan.FromSeconds(125)
    };

    private readonly ConcurrentQueue<string> messagesToShow = new();
    private bool requestInProgress;

    private const int ButtonWidth = 190;
    private const int ButtonHeight = 54;
    private const int ButtonMargin = 20;

    public override void Entry(IModHelper helper)
    {
        helper.Events.Display.RenderingHud += this.OnRenderingHud;
        helper.Events.Input.ButtonPressed += this.OnButtonPressed;
        helper.Events.GameLoop.UpdateTicked += this.OnUpdateTicked;

        this.Monitor.Log(
            "AI Villagers loaded. Keep the AI Villagers bridge app open with Gemma loaded. Tap the in-game 'Проверить ИИ' button to test the connection.",
            LogLevel.Info
        );
    }

    private void OnRenderingHud(object? sender, StardewModdingAPI.Events.RenderingHudEventArgs e)
    {
        if (!Context.IsWorldReady)
            return;

        int x = Game1.viewport.Width - ButtonWidth - ButtonMargin;
        int y = Game1.viewport.Height - ButtonHeight - ButtonMargin;
        var bounds = new Rectangle(x, y, ButtonWidth, ButtonHeight);

        Color background = this.requestInProgress
            ? new Color(85, 85, 85, 235)
            : new Color(45, 105, 70, 235);

        e.SpriteBatch.Draw(Game1.staminaRect, bounds, background);

        const string label = "Проверить ИИ";
        Vector2 size = Game1.smallFont.MeasureString(label);
        Vector2 position = new(
            x + (ButtonWidth - size.X) / 2f,
            y + (ButtonHeight - size.Y) / 2f
        );
        e.SpriteBatch.DrawString(Game1.smallFont, label, position, Color.White);
    }

    private void OnButtonPressed(object? sender, StardewModdingAPI.Events.ButtonPressedEventArgs e)
    {
        if (!Context.IsWorldReady || Game1.activeClickableMenu != null)
            return;
        if (e.Button != SButton.MouseLeft)
            return;

        int x = Game1.viewport.Width - ButtonWidth - ButtonMargin;
        int y = Game1.viewport.Height - ButtonHeight - ButtonMargin;
        if (!new Rectangle(x, y, ButtonWidth, ButtonHeight)
            .Contains(Game1.getMouseX(), Game1.getMouseY()))
            return;

        if (this.requestInProgress)
        {
            this.messagesToShow.Enqueue("ИИ уже готовит ответ. Подожди немного.");
            return;
        }

        this.requestInProgress = true;
        _ = this.SendTestRequestAsync();
    }

    private async Task SendTestRequestAsync()
    {
        try
        {
            var payload = JsonSerializer.Serialize(new
            {
                npc = "Abigail",
                message = "Привет! Как у тебя сегодня настроение? Ответь как жительница долины, по-русски и естественно.",
                context = "Игрок нажал кнопку проверки ИИ в Stardew Valley. Это тестовый разговор с Абигейл."
            });

            using var content = new StringContent(payload, Encoding.UTF8, "application/json");
            using var response = await Client.PostAsync("http://127.0.0.1:8765/chat", content);
            string body = await response.Content.ReadAsStringAsync();

            if (!response.IsSuccessStatusCode)
            {
                this.messagesToShow.Enqueue("Ошибка Gemma: HTTP " + (int)response.StatusCode);
                return;
            }

            string reply = body;
            try
            {
                using JsonDocument document = JsonDocument.Parse(body);
                if (document.RootElement.TryGetProperty("reply", out JsonElement replyElement))
                    reply = replyElement.GetString() ?? body;
            }
            catch
            {
                // Show the raw response if it isn't JSON.
            }

            this.messagesToShow.Enqueue("Абигейл: " + reply);
        }
        catch (Exception ex)
        {
            this.messagesToShow.Enqueue(
                "Нет соединения с AI Villagers (127.0.0.1:8765). Открой приложение AI Villagers, загрузи Gemma и оставь его работать. Подробности: " + ex.GetType().Name + ": " + ex.Message
            );
        }
        finally
        {
            this.requestInProgress = false;
        }
    }

    private void OnUpdateTicked(object? sender, StardewModdingAPI.Events.UpdateTickedEventArgs e)
    {
        while (this.messagesToShow.TryDequeue(out string? message))
            Game1.showGlobalMessage(message);
    }
}
