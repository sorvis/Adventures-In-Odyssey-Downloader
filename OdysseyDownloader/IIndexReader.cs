using Odyssey_Downloader.Model;
using System.Collections.Generic;

namespace Odyssey_Downloader
{
    public interface IIndexReader
    {
        IEnumerable<AudioFile> RebuildIndex(Config settings);

        bool IndexDetected();
    }
}