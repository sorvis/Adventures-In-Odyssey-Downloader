namespace Odyssey_Downloader
{
    public interface IGetFileInfo
    {
        string EpisodeNumber { get; }
        string FileName { get; }
        string FileUrl { get; }
        string FullTitle { get; }
    }
}