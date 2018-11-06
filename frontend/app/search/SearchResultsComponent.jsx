import React from 'react';
import PropTypes from 'prop-types';
import EntryView from './EntryView.jsx';

class SearchResultsComponent extends React.Component {
    static propTypes = {
        entries: PropTypes.array.isRequired
    };

    render(){
        return <div className="search-results-container">
            {
                this.props.entries.map(entry=><EntryView entry={entry}/>)
            }
        </div>
    }
}

export default SearchResultsComponent;