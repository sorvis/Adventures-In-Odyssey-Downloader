namespace Odyssey_Downloader
{
    public interface IDownloader
    {
        void GetFile(string url, string fileName);
    }
}