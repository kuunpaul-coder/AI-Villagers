using System.Net.Http;
using System.Text;
using System.Text.Json;
using StardewModdingAPI;

namespace AI.Villagers;

public sealed class ModEntry : Mod
{
    private static readonly HttpClient Client = new()
    {
        Timeout = TimeSpan.FromSeconds(125)
    };

    public override void Entry(IModHelper helper)
    {
        helper.ConsoleCommands.Add(
            "aivillagers.test",
            "Tests the connection to the local Gemma bridge. Usage: aivillagers.test [NPC name]",
            this.TestGemma
        );

        this.Monitor.Log(
            "AI Villagers loaded. Keep the AI Villagers bridge app open with Gemma loaded. Run aivillagers.test to test the connection.",
            LogLevel.Info
        );
    }

    private async void TestGemma(string command, string[] args)
    {
        string npc = args.Length > 0 ? string.Join(" ", args) : "Abigail";
        var payload = JsonSerializer.Serialize(new
        {
            npc,
            message = "Привет! Как у тебя сегодня настроение?",
            context = "Это проверка связи мода AI Villagers с локальной моделью Gemma."
        });

        try
        {
            using var content = new StringContent(payload, Encoding.UTF8, "application/json");
            using var response = await Client.PostAsync("http://127.0.0.1:8765/chat", content);
            string body = await response.Content.ReadAsStringAsync();

            if (response.IsSuccessStatusCode)
                this.Monitor.Log("Ответ Gemma: " + body, LogLevel.Info);
            else
                this.Monitor.Log("Gemma вернула HTTP " + (int)response.StatusCode + ": " + body, LogLevel.Warn);
        }
        catch (Exception ex)
        {
            this.Monitor.Log(
                "Не удалось связаться с Gemma. Убедись, что приложение AI Villagers открыто и модель загружена. " + ex.Message,
                LogLevel.Error
            );
        }
    }
}
