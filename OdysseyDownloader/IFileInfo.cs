namespace Odyssey_Downloader
{
    public interface IFileInfo
    {
        string EpisodeNumber { get; }
        string FileName { get; }
        string FileUrl { get; }
        string Title { get; }
    }
}