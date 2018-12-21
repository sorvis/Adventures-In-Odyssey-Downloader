using System;
using System.IO;
using System.Text.RegularExpressions;

namespace Odyssey_Downloader
{
    public interface IIndexReader
    {
        void RebuildIndex();
    }
}
