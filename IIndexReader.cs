namespace Odyssey_Downloader
{
    public interface IIndexReader
    {
        void RebuildIndex(Config settings);

        bool IndexDetected();
    }
}