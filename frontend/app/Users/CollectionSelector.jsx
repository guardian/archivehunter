import React from 'react';
import PropTypes from 'prop-types';

class CollectionSelector extends React.Component {
    static propTypes = {
        collections: PropTypes.array.isRequired,
        userSelected: PropTypes.array.isRequired,
        selectionUpdated: PropTypes.func,
        disabled: PropTypes.bool
    };

    constructor(props){
        super(props);

        this.selectionUpdated = this.selectionUpdated.bind(this);
    }

    selectionUpdated(evt){
        console.log("selectionUpdated: ", evt.target.getAttribute("data-key"), evt.target.checked);
        const updatedValue = evt.target.checked ? this.props.userSelected.concat(evt.target.getAttribute("data-key")) : this.props.userSelected.filter(entry=>entry!==evt.target.getAttribute("data-key"));
        if(this.props.selectionUpdated){
            this.props.selectionUpdated(updatedValue)
        }
    }

    render(){
        return <ul className="collections-list">
            {
                this.props.collections.map(collectionName=>
                    <li key={collectionName} className={this.props.disabled ? "collection-list-item-disabled" : "collection-list-item"}>
                        <input type="checkbox"
                                disabled={this.props.disabled}
                                data-key={collectionName}
                                checked={this.props.userSelected ? this.props.userSelected.includes(collectionName) : false}
                                onChange={this.selectionUpdated}/>{collectionName}</li>)
            }
        </ul>
    }
}

export default CollectionSelector;