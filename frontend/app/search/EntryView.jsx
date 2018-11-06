import React from 'react';
import PropTypes from 'prop-types';

class EntryView extends React.Component {
    static propTypes = {
        entry: PropTypes.object.isRequired
    };

    filename(){
        const fnParts = this.props.entry.path.split("/");
        return fnParts.slice(-1);
    }
    render(){
        return <div className="entry-view">
            <p className="entry-title">{this.filename()}</p>
        </div>
    }
}

export default EntryView;