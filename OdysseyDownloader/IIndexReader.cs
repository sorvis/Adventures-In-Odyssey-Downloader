using Odyssey_Downloader.Model;
using System.Collections.Generic;

namespace Odyssey_Downloader
{
    public interface IIndexReader
    {
        IEnumerable<AudioFile> RebuildIndex();

        IEnumerable<AudioFile> ReadIndex();

        void WriteToIndex(AudioFile fileInfo);

        bool IndexDetected();
    }
}